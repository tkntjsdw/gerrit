/**
 * @license
 * Copyright (C) 2016 The Android Open Source Project
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

import '../../../test/common-test-setup-karma';
import './gr-account-entry';
import {GrAccountEntry} from './gr-account-entry';

const basicFixture = fixtureFromElement('gr-account-entry');

suite('gr-account-entry tests', () => {
  let element: GrAccountEntry;

  setup(() => {
    element = basicFixture.instantiate();
  });

  test('account-text-changed fired when input text changed and allowAnyInput', () => {
    // Spy on query, as that is called when _updateSuggestions proceeds.
    const changeStub = sinon.stub();
    element.allowAnyInput = true;
    element.querySuggestions = () => Promise.resolve([]);
    element.addEventListener('account-text-changed', changeStub);
    element.$.input.text = 'a';
    assert.isTrue(changeStub.calledOnce);
    element.$.input.text = 'ab';
    assert.isTrue(changeStub.calledTwice);
  });

  test(
    'account-text-changed not fired when input text changed without ' +
      'allowAnyInput',
    () => {
      // Spy on query, as that is called when _updateSuggestions proceeds.
      const changeStub = sinon.stub();
      element.querySuggestions = () => Promise.resolve([]);
      element.addEventListener('account-text-changed', changeStub);
      element.$.input.text = 'a';
      assert.isFalse(changeStub.called);
    }
  );

  test('setText', () => {
    // Spy on query, as that is called when _updateSuggestions proceeds.
    const suggestSpy = sinon.spy(element.$.input, 'query');
    element.setText('test text');
    flush();

    assert.equal(element.$.input.$.input.value, 'test text');
    assert.isFalse(suggestSpy.called);
  });
});
