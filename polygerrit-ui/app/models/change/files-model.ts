/**
 * @license
 * Copyright 2022 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import {
  BasePatchSetNum,
  FileInfo,
  FileNameToFileInfoMap,
  PARENT,
  PatchRange,
  PatchSetNumber,
  RevisionPatchSetNum,
} from '../../types/common';
import {combineLatest, from, Observable, of} from 'rxjs';
import {map, switchMap} from 'rxjs/operators';
import {RestApiService} from '../../services/gr-rest-api/gr-rest-api';
import {select} from '../../utils/observable-util';
import {FileInfoStatus, SpecialFilePath} from '../../constants/constants';
import {specialFilePathCompare} from '../../utils/path-list-util';
import {Model} from '../base/model';
import {define} from '../dependency';
import {ChangeModel} from './change-model';
import {CommentsModel} from '../comments/comments-model';
import {Timing} from '../../constants/reporting';
import {ReportingService} from '../../services/gr-reporting/gr-reporting';
import {RunResult} from '../checks/checks-model';
import {ChecksModel} from '../checks/checks-model';

export type FileNameToNormalizedFileInfoMap = {
  [name: string]: NormalizedFileInfo;
};
export interface NormalizedFileInfo extends FileInfo {
  __path: string;
  // Compared to `FileInfo` these four props are required here.
  lines_inserted: number;
  lines_deleted: number;
  size_delta: number; // in bytes
  size: number; // in bytes
}

export function normalize(file: FileInfo, path: string): NormalizedFileInfo {
  return {
    __path: path,
    // These 4 props are required in NormalizedFileInfo, but optional in
    // FileInfo. So let's set a default value, if not already set.
    lines_inserted: 0,
    lines_deleted: 0,
    size_delta: 0,
    size: 0,
    ...file,
  };
}

function mapToList(map?: FileNameToFileInfoMap): NormalizedFileInfo[] {
  const list: NormalizedFileInfo[] = [];
  for (const [key, value] of Object.entries(map ?? {})) {
    list.push(normalize(value, key));
  }
  list.sort((f1, f2) => specialFilePathCompare(f1.__path, f2.__path));
  return list;
}

export function addUnmodified(
  files: NormalizedFileInfo[],
  commentedPaths: string[],
  checkResults?: RunResult[]
) {
  const combined = [...files];
  // Add paths from comments
  for (const commentedPath of commentedPaths) {
    if (commentedPath === SpecialFilePath.PATCHSET_LEVEL_COMMENTS) continue;
    if (files.some(f => f.__path === commentedPath)) continue;
    if (
      files.some(
        f => f.status === FileInfoStatus.RENAMED && f.old_path === commentedPath
      )
    ) {
      continue;
    }
    combined.push(
      normalize({status: FileInfoStatus.UNMODIFIED}, commentedPath)
    );
  }

  // Add paths from check results
  if (checkResults) {
    for (const result of checkResults) {
      if (!result.codePointers?.length) continue;
      for (const pointer of result.codePointers) {
        const path = pointer.path;
        if (!path) continue;
        if (files.some(f => f.__path === path)) continue;
        if (
          files.some(
            f => f.status === FileInfoStatus.RENAMED && f.old_path === path
          )
        ) {
          continue;
        }
        if (combined.some(f => f.__path === path)) continue;
        combined.push(normalize({status: FileInfoStatus.UNMODIFIED}, path));
      }
    }
  }

  combined.sort((f1, f2) => specialFilePathCompare(f1.__path, f2.__path));
  return combined;
}

export interface FilesState {
  // TODO: Move reviewed files from change model into here. Change model is
  // already large and complex, so the files model is a better fit.

  /**
   * Basic file and diff information of all files for the currently chosen
   * patch range.
   */
  files: NormalizedFileInfo[];

  /**
   * Basic file and diff information of all files for the left chosen patchset
   * compared against its base (aka parent).
   *
   * Empty if the left chosen patchset is PARENT.
   */
  filesLeftBase: NormalizedFileInfo[];

  /**
   * Basic file and diff information of all files for the right chosen patchset
   * compared against its base (aka parent).
   *
   * Empty if the left chosen patchset is PARENT.
   */
  filesRightBase: NormalizedFileInfo[];
}

const initialState: FilesState = {
  files: [],
  filesLeftBase: [],
  filesRightBase: [],
};

export const filesModelToken = define<FilesModel>('files-model');

export class FilesModel extends Model<FilesState> {
  public readonly files$ = select(this.state$, state => state.files);

  public file$ = (path$: Observable<string | undefined>) =>
    combineLatest([path$, this.files$]).pipe(
      map(([path, files]) => files.find(f => f.__path === path))
    );

  /**
   * `files$` only includes the files that were modified. Here we also include
   * all unmodified files that have comments with
   * `status: FileInfoStatus.UNMODIFIED` and files referenced in check results.
   */
  public readonly filesIncludingUnmodified$;

  public readonly filesLeftBase$;

  public readonly filesRightBase$;

  constructor(
    readonly changeModel: ChangeModel,
    readonly commentsModel: CommentsModel,
    readonly checksModel: ChecksModel,
    readonly restApiService: RestApiService,
    private readonly reporting: ReportingService
  ) {
    super(initialState);

    this.filesIncludingUnmodified$ = select(
      combineLatest([
        this.files$,
        this.commentsModel.commentedPaths$,
        this.checksModel.allResults$,
      ]),
      ([files, commentedPaths, checkResults]) =>
        addUnmodified(files, commentedPaths, checkResults)
    );
    this.filesLeftBase$ = select(this.state$, state => state.filesLeftBase);
    this.filesRightBase$ = select(this.state$, state => state.filesRightBase);

    this.subscriptions = [
      this.reportChangeDataStart(),
      this.reportChangeDataEnd(),
      this.subscribeToFiles(
        (psLeft, psRight) => {
          return {basePatchNum: psLeft, patchNum: psRight};
        },
        files => {
          return {files: [...files]};
        }
      ),
      this.subscribeToFiles(
        (psLeft, _) => {
          if (psLeft === PARENT || (psLeft as PatchSetNumber) <= 0)
            return undefined;
          return {basePatchNum: PARENT, patchNum: psLeft as PatchSetNumber};
        },
        files => {
          return {filesLeftBase: [...files]};
        }
      ),
      this.subscribeToFiles(
        (psLeft, psRight) => {
          if (psLeft === PARENT || (psLeft as PatchSetNumber) <= 0)
            return undefined;
          return {basePatchNum: PARENT, patchNum: psRight as PatchSetNumber};
        },
        files => {
          return {filesRightBase: [...files]};
        }
      ),
    ];
  }

  private reportChangeDataStart() {
    return combineLatest([this.changeModel.loading$]).subscribe(
      ([changeLoading]) => {
        if (changeLoading) {
          this.reporting.time(Timing.CHANGE_DATA);
        }
      }
    );
  }

  private reportChangeDataEnd() {
    return combineLatest([this.changeModel.loading$, this.files$]).subscribe(
      ([changeLoading, files]) => {
        if (!changeLoading && files.length > 0) {
          this.reporting.timeEnd(Timing.CHANGE_DATA);
        }
      }
    );
  }

  private subscribeToFiles(
    rangeChooser: (
      basePatchNum: BasePatchSetNum,
      patchNum: RevisionPatchSetNum
    ) => PatchRange | undefined,
    filesToState: (files: NormalizedFileInfo[]) => Partial<FilesState>
  ) {
    return combineLatest([
      this.changeModel.changeNum$,
      this.changeModel.basePatchNum$,
      this.changeModel.patchNum$,
    ])
      .pipe(
        switchMap(([changeNum, basePatchNum, patchNum]) => {
          if (!changeNum || !patchNum) return of({});
          const range = rangeChooser(basePatchNum, patchNum);
          if (!range) return of({});
          return from(
            this.restApiService.getChangeOrEditFiles(changeNum, range)
          );
        }),
        map(mapToList),
        map(filesToState)
      )
      .subscribe(state => {
        this.updateState(state);
      });
  }
}
