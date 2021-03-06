/**
 * @license
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import '@polymer/iron-input/iron-input';
import '../../../styles/shared-styles';
import '../gr-button/gr-button';
import '../gr-icons/gr-icons';
import {dom, EventApi} from '@polymer/polymer/lib/legacy/polymer.dom';
import {PolymerElement} from '@polymer/polymer/polymer-element';
import {htmlTemplate} from './gr-copy-clipboard_html';
import {GrButton} from '../gr-button/gr-button';
import {customElement, property} from '@polymer/decorators';
import {IronIconElement} from '@polymer/iron-icon';
import {assertIsDefined} from '../../../utils/common-util';

const COPY_TIMEOUT_MS = 1000;

declare global {
  interface HTMLElementTagNameMap {
    'gr-copy-clipboard': GrCopyClipboard;
  }
}

export interface GrCopyClipboard {
  $: {button: GrButton; icon: IronIconElement; input: HTMLInputElement};
}

@customElement('gr-copy-clipboard')
export class GrCopyClipboard extends PolymerElement {
  static get template() {
    return htmlTemplate;
  }

  @property({type: String})
  text: string | undefined;

  @property({type: String})
  buttonTitle: string | undefined;

  @property({type: Boolean})
  hasTooltip = false;

  @property({type: Boolean})
  hideInput = false;

  focusOnCopy() {
    this.$.button.focus();
  }

  _computeInputClass(hideInput: boolean) {
    return hideInput ? 'hideInput' : '';
  }

  _handleInputClick(e: MouseEvent) {
    e.preventDefault();
    ((dom(e) as EventApi).rootTarget as HTMLInputElement).select();
  }

  _copyToClipboard(e: MouseEvent) {
    e.preventDefault();
    e.stopPropagation();

    assertIsDefined(this.text, 'text');
    this.$.icon.icon = 'gr-icons:check';
    navigator.clipboard.writeText(this.text);
    setTimeout(
      () => (this.$.icon.icon = 'gr-icons:content-copy'),
      COPY_TIMEOUT_MS
    );
  }
}
