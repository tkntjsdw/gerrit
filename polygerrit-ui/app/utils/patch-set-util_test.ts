/**
 * @license
 * Copyright 2016 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import {assert} from '@open-wc/testing';
import '../test/common-test-setup';
import {
  createChange,
  createChangeMessageInfo,
  createRevision,
} from '../test/test-data-generators';
import {
  BasePatchSetNum,
  ChangeInfo,
  CommitId,
  EDIT,
  PARENT,
  PatchSetNum,
  PatchSetNumber,
  ReviewInputTag,
  RevisionInfo,
} from '../types/common';
import {
  _testOnly_computeWipForPatchSets,
  computeAllPatchSets,
  findEditParentPatchNum,
  findEditParentRevision,
  getParentIndex,
  getRevisionByPatchNum,
  isMergeParent,
  sortRevisions,
} from './patch-set-util';
import {EditRevisionInfo} from '../types/types';

suite('gr-patch-set-util tests', () => {
  test('getRevisionByPatchNum', () => {
    const revisions = [createRevision(0), createRevision(1), createRevision(2)];
    assert.deepEqual(
      getRevisionByPatchNum(revisions, 1 as PatchSetNum),
      revisions[1]
    );
    assert.deepEqual(
      getRevisionByPatchNum(revisions, 2 as PatchSetNum),
      revisions[2]
    );
    assert.equal(getRevisionByPatchNum(revisions, 3 as PatchSetNum), undefined);
  });

  test('_computeWipForPatchSets', () => {
    // Compute patch sets for a given timeline on a change. The initial WIP
    // property of the change can be true or false. The map of tags by
    // revision is keyed by patch set number. Each value is a list of change
    // message tags in the order that they occurred in the timeline. These
    // indicate actions that modify the WIP property of the change and/or
    // create new patch sets.
    //
    // Returns the actual results with an assertWip method that can be used
    // to compare against an expected value for a particular patch set.
    const compute = (
      initialWip: boolean,
      tagsByRevision: Map<PatchSetNumber, (ReviewInputTag | undefined)[]>
    ) => {
      const change: ChangeInfo = {
        ...createChange(),
        messages: [],
        work_in_progress: initialWip,
      };
      for (const rev of tagsByRevision.keys()) {
        for (const tag of tagsByRevision.get(rev)!) {
          change.messages!.push({
            ...createChangeMessageInfo(),
            tag,
            _revision_number: rev,
          });
        }
      }
      const patchSets = Array.from(tagsByRevision.keys()).map(rev => {
        return {num: rev, desc: 'test', sha: `rev${rev}` as CommitId};
      });
      const patchNums = _testOnly_computeWipForPatchSets(change, patchSets);
      const verifier = {
        assertWip(revision: number, expectedWip: boolean) {
          const patchNum = patchNums.find(
            patchNum => patchNum.num === (revision as PatchSetNum)
          );
          if (!patchNum) {
            assert.fail(`revision ${revision} not found`);
          }
          assert.equal(
            patchNum.wip,
            expectedWip,
            `wip state for ${revision} ` +
              `is ${patchNum.wip}; expected ${expectedWip}`
          );
          return verifier;
        },
      };
      return verifier;
    };

    const upload = 'upload' as ReviewInputTag;

    compute(false, new Map([[1 as PatchSetNumber, [upload]]])).assertWip(
      1,
      false
    );
    compute(true, new Map([[1 as PatchSetNumber, [upload]]])).assertWip(
      1,
      true
    );

    const setWip = 'autogenerated:gerrit:setWorkInProgress' as ReviewInputTag;
    const uploadInWip = 'autogenerated:gerrit:newWipPatchSet' as ReviewInputTag;
    const clearWip = 'autogenerated:gerrit:setReadyForReview' as ReviewInputTag;

    compute(
      false,
      new Map([
        [1 as PatchSetNumber, [upload, setWip]],
        [2 as PatchSetNumber, [upload]],
        [3 as PatchSetNumber, [upload, clearWip]],
        [4 as PatchSetNumber, [upload, setWip]],
      ])
    )
      .assertWip(1, false) // Change was created with PS1 ready for review
      .assertWip(2, true) // PS2 was uploaded during WIP
      .assertWip(3, false) // PS3 was marked ready for review after upload
      .assertWip(4, false); // PS4 was uploaded ready for review

    compute(
      false,
      new Map([
        [
          1 as PatchSetNumber,
          [uploadInWip, undefined, 'addReviewer' as ReviewInputTag],
        ],
        [2 as PatchSetNumber, [upload]],
        [3 as PatchSetNumber, [upload, clearWip, setWip]],
        [4 as PatchSetNumber, [upload]],
        [5 as PatchSetNumber, [upload, clearWip]],
        [6 as PatchSetNumber, [uploadInWip]],
      ])
    )
      .assertWip(1, true) // Change was created in WIP
      .assertWip(2, true) // PS2 was uploaded during WIP
      .assertWip(3, false) // PS3 was marked ready for review
      .assertWip(4, true) // PS4 was uploaded during WIP
      .assertWip(5, false) // PS5 was marked ready for review
      .assertWip(6, true); // PS6 was uploaded with WIP option
  });

  test('isMergeParent', () => {
    assert.isFalse(isMergeParent(1 as PatchSetNum));
    assert.isFalse(isMergeParent(4321 as PatchSetNum));
    assert.isFalse(isMergeParent(EDIT as PatchSetNum));
    assert.isFalse(isMergeParent(PARENT as PatchSetNum));
    assert.isFalse(isMergeParent(0 as PatchSetNum));

    assert.isTrue(isMergeParent(-23 as PatchSetNum));
    assert.isTrue(isMergeParent(-1 as PatchSetNum));
  });

  test('findEditParentRevision', () => {
    const revisions: Array<RevisionInfo | EditRevisionInfo> = [
      createRevision(0),
      createRevision(1),
      createRevision(2),
    ];
    assert.strictEqual(findEditParentRevision(revisions), null);

    revisions.push({
      ...createRevision(EDIT),
      basePatchNum: 3 as BasePatchSetNum,
    });
    assert.strictEqual(findEditParentRevision(revisions), null);

    revisions.push(createRevision(3));
    assert.deepEqual(findEditParentRevision(revisions), createRevision(3));
  });

  test('findEditParentPatchNum', () => {
    const revisions: Array<RevisionInfo | EditRevisionInfo> = [
      createRevision(0),
      createRevision(1),
      createRevision(2),
    ];
    assert.equal(findEditParentPatchNum(revisions), -1);

    revisions.push(
      {
        ...createRevision(EDIT),
        basePatchNum: 3 as BasePatchSetNum,
      },
      createRevision(3)
    );
    assert.deepEqual(findEditParentPatchNum(revisions), 3);
  });

  test('sortRevisions', () => {
    const revisions: Array<RevisionInfo | EditRevisionInfo> = [
      createRevision(0),
      createRevision(2),
      createRevision(1),
    ];
    const sorted: Array<RevisionInfo | EditRevisionInfo> = [
      createRevision(2),
      createRevision(1),
      createRevision(0),
    ];

    assert.deepEqual(sortRevisions(revisions), sorted);

    // Edit patchset should follow directly after its basePatchNum.
    revisions.push({
      ...createRevision(EDIT),
      basePatchNum: 2 as BasePatchSetNum,
    });
    sorted.unshift({
      ...createRevision(EDIT),
      basePatchNum: 2 as BasePatchSetNum,
    });
    assert.deepEqual(sortRevisions(revisions), sorted);

    (revisions[0] as EditRevisionInfo).basePatchNum = 0 as BasePatchSetNum;
    const edit = sorted.shift() as EditRevisionInfo;
    edit.basePatchNum = 0 as BasePatchSetNum;
    // Edit patchset should be at index 2.
    sorted.splice(2, 0, edit);
    assert.deepEqual(sortRevisions(revisions), sorted);
  });

  test('getParentIndex', () => {
    assert.equal(getParentIndex(-4 as PatchSetNum), 4);
  });

  test('computeAllPatchSets', () => {
    const expected = [
      {num: 4 as PatchSetNumber, desc: 'test', sha: 'rev4' as CommitId},
      {num: 3 as PatchSetNumber, desc: 'test', sha: 'rev3' as CommitId},
      {num: 2 as PatchSetNumber, desc: 'test', sha: 'rev2' as CommitId},
      {num: 1 as PatchSetNumber, desc: 'test', sha: 'rev1' as CommitId},
    ];
    const patchNums = computeAllPatchSets({
      ...createChange(),
      revisions: {
        rev1: {...createRevision(1), description: 'test'},
        rev2: {...createRevision(2), description: 'test'},
        rev3: {...createRevision(3), description: 'test'},
        rev4: {...createRevision(4), description: 'test'},
      },
    });
    assert.equal(patchNums.length, expected.length);
    for (let i = 0; i < expected.length; i++) {
      assert.deepEqual(patchNums[i], expected[i]);
    }
  });
});
