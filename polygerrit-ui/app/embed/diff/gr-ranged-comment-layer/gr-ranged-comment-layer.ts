/**
 * @license
 * Copyright 2016 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import {GrAnnotationImpl} from '../gr-diff-highlight/gr-annotation';
import {GrDiffLine} from '../gr-diff/gr-diff-line';
import {strToClassName} from '../../../utils/dom-util';
import {Side} from '../../../constants/constants';
import {CommentRange} from '../../../types/common';
import {DiffLayer, DiffLayerListener} from '../../../types/types';
import {isLongCommentRange} from '../gr-diff/gr-diff-utils';
import {CommentRangeLayer, GrDiffLineType} from '../../../api/diff';
import {rangeId} from '../../../utils/comment-util';

/** Can be used for array functions like `some()`. */
function equals(a: CommentRangeLayer) {
  return (b: CommentRangeLayer) => id(a) === id(b);
}

export function id(r: CommentRangeLayer): string {
  if (r.id) return r.id;
  return `${r.side}-${rangeId(r.range)}`;
}

/**
 * This class breaks down all comment ranges into individual line segment
 * highlights.
 */
interface CommentRangeLineLayer {
  longRange: boolean;
  id: string;
  // start char (0-based)
  start: number;
  // end char (0-based)
  end: number;
}

type LinesMap = {
  [line in number]: CommentRangeLineLayer[];
};

type RangesMap = {
  [side in Side]: LinesMap;
};

const RANGE_BASE_ONLY = 'range';
const RANGE_HIGHLIGHT = 'range rangeHighlight';
// Note that there is also `rangeHoverHighlight` being set by GrDiffHighlight.

/**
 * This layer does not have a `reset` or `cleanup` method, so don't re-use it
 * for rendering another diff. You should create a new layer then.
 */
export class GrRangedCommentLayer implements DiffLayer {
  private knownRanges: CommentRangeLayer[] = [];

  private listeners: DiffLayerListener[] = [];

  private rangesMap: RangesMap = {left: {}, right: {}};

  /**
   * Layer method to add annotations to a line.
   *
   * @param el The DIV.contentText element to apply the annotation to.
   */
  annotate(el: HTMLElement, _: HTMLElement, line: GrDiffLine) {
    let ranges: CommentRangeLineLayer[] = [];
    if (
      line.type === GrDiffLineType.REMOVE ||
      (line.type === GrDiffLineType.BOTH &&
        el.getAttribute('data-side') !== Side.RIGHT)
    ) {
      ranges = this.getRangesForLine(line, Side.LEFT);
    }
    if (
      line.type === GrDiffLineType.ADD ||
      (line.type === GrDiffLineType.BOTH &&
        el.getAttribute('data-side') !== Side.LEFT)
    ) {
      ranges = this.getRangesForLine(line, Side.RIGHT);
    }

    for (const range of ranges) {
      GrAnnotationImpl.annotateElement(
        el,
        range.start,
        range.end - range.start,
        (range.longRange ? RANGE_BASE_ONLY : RANGE_HIGHLIGHT) +
          ` ${strToClassName(range.id)}`
      );
    }
  }

  /**
   * Register a listener for layer updates.
   */
  addListener(listener: DiffLayerListener) {
    this.listeners.push(listener);
  }

  removeListener(listener: DiffLayerListener) {
    this.listeners = this.listeners.filter(f => f !== listener);
  }

  /**
   * Notify Layer listeners of changes to annotations.
   */
  private notifyUpdateRange(start: number, end: number, side: Side) {
    for (const listener of this.listeners) {
      listener(start, end, side);
    }
  }

  updateRanges(newRanges: CommentRangeLayer[]) {
    for (const newRange of newRanges) {
      if (this.knownRanges.some(equals(newRange))) continue;
      this.addRange(newRange);
    }

    for (const knownRange of this.knownRanges) {
      if (newRanges.some(equals(knownRange))) continue;
      this.removeRange(knownRange);
    }

    this.knownRanges = [...newRanges];
  }

  private addRange(commentRange: CommentRangeLayer) {
    const {side, range} = commentRange;
    const longRange = isLongCommentRange(range);
    this.updateRangesMap({
      side,
      range,
      operation: (forLine, startChar, endChar) => {
        if (startChar !== endChar)
          forLine.push({
            start: startChar,
            end: endChar,
            id: id(commentRange),
            longRange,
          });
      },
    });
  }

  private removeRange(commentRange: CommentRangeLayer) {
    const {side, range} = commentRange;
    this.updateRangesMap({
      side,
      range,
      operation: forLine => {
        const index = forLine.findIndex(
          lineRange => id(commentRange) === lineRange.id
        );
        if (index > -1) forLine.splice(index, 1);
      },
    });
  }

  private updateRangesMap(options: {
    side: Side;
    range: CommentRange;
    operation: (
      forLine: CommentRangeLineLayer[],
      start: number,
      end: number
    ) => void;
  }) {
    const {side, range, operation} = options;
    const forSide = this.rangesMap[side] || (this.rangesMap[side] = {});
    for (let line = range.start_line; line <= range.end_line; line++) {
      const forLine = forSide[line] || (forSide[line] = []);
      const start = line === range.start_line ? range.start_character : 0;
      const end = line === range.end_line ? range.end_character : -1;
      operation(forLine, start, end);
    }
    this.notifyUpdateRange(range.start_line, range.end_line, side);
  }

  // visible for testing
  getRangesForLine(line: GrDiffLine, side: Side): CommentRangeLineLayer[] {
    const lineNum = side === Side.LEFT ? line.beforeNumber : line.afterNumber;
    if (typeof lineNum !== 'number') return [];
    const ranges: CommentRangeLineLayer[] = this.rangesMap[side][lineNum] || [];
    return ranges.map(range => {
      // Make a copy, so that the normalization below does not mess with
      // our map.
      range = {...range};
      range.end = range.end === -1 ? line.text.length : range.end;

      // Normalize invalid ranges where the start is after the end but the
      // start still makes sense. Set the end to the end of the line.
      // @see Issue 5744
      if (range.start > range.end && range.start < line.text.length) {
        range.end = line.text.length;
      }

      return range;
    });
  }
}
