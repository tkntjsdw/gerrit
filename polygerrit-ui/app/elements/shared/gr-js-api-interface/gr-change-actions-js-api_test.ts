/**
 * @license
 * Copyright 2016 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import * as sinon from 'sinon';
import '../../../test/common-test-setup';
import '../../change/gr-change-actions/gr-change-actions';
import {query, queryAll, queryAndAssert} from '../../../test/test-utils';
import {GrChangeActions} from '../../change/gr-change-actions/gr-change-actions';
import {assert, fixture, html} from '@open-wc/testing';
import {PluginApi} from '../../../api/plugin';
import {
  ActionPriority,
  ActionType,
  ChangeActionsPluginApi,
  PrimaryActionKey,
} from '../../../api/change-actions';
import {GrButton} from '../gr-button/gr-button';
import {ChangeViewChangeInfo} from '../../../types/common';
import {GrDropdown} from '../gr-dropdown/gr-dropdown';
import {GrIcon} from '../gr-icon/gr-icon';
import {testResolver} from '../../../test/common-test-setup';
import {pluginLoaderToken} from './gr-plugin-loader';

suite('gr-change-actions-js-api-interface tests', () => {
  let element: GrChangeActions;
  let changeActions: ChangeActionsPluginApi;
  let plugin: PluginApi;

  suite('early init', () => {
    setup(async () => {
      window.Gerrit.install(
        p => {
          plugin = p;
        },
        '0.1',
        'http://test.com/plugins/testplugin/static/test.js'
      );
      // Mimic all plugins loaded.
      testResolver(pluginLoaderToken).loadPlugins([]);
      changeActions = plugin.changeActions();
      element = await fixture<GrChangeActions>(html`
        <gr-change-actions></gr-change-actions>
      `);
    });

    test('does not throw', () => {
      assert.doesNotThrow(() => {
        changeActions.add(ActionType.CHANGE, 'foo');
      });
    });
  });

  suite('normal init', () => {
    setup(async () => {
      element = await fixture<GrChangeActions>(html`
        <gr-change-actions></gr-change-actions>
      `);
      element.change = {} as ChangeViewChangeInfo;
      element.revisionActions = {};
      window.Gerrit.install(
        p => {
          plugin = p;
        },
        '0.1',
        'http://test.com/plugins/testplugin/static/test.js'
      );
      changeActions = plugin.changeActions();
      // Mimic all plugins loaded.
      testResolver(pluginLoaderToken).loadPlugins([]);
    });

    test('add/remove primary action keys', () => {
      element.primaryActionKeys = [];
      changeActions.addPrimaryActionKey('foo' as PrimaryActionKey);
      assert.deepEqual(element.primaryActionKeys, ['foo']);
      changeActions.addPrimaryActionKey('bar' as PrimaryActionKey);
      assert.deepEqual(element.primaryActionKeys, ['foo', 'bar']);
      changeActions.removePrimaryActionKey('foo');
      assert.deepEqual(element.primaryActionKeys, ['bar']);
      changeActions.removePrimaryActionKey('baz');
      assert.deepEqual(element.primaryActionKeys, ['bar']);
      changeActions.removePrimaryActionKey('bar');
      assert.deepEqual(element.primaryActionKeys, []);
    });

    test('action buttons', async () => {
      const key = changeActions.add(ActionType.REVISION, 'Bork!');
      const handler = sinon.spy();
      changeActions.addTapListener(key, handler);
      await element.updateComplete;
      queryAndAssert<GrButton>(element, `[data-action-key="${key}"]`).click();
      await element.updateComplete;
      assert(handler.calledOnce);
      changeActions.removeTapListener(key, handler);
      await element.updateComplete;
      queryAndAssert<GrButton>(element, `[data-action-key="${key}"]`).click();
      await element.updateComplete;
      assert(handler.calledOnce);
      changeActions.remove(key);
      await element.updateComplete;
      assert.isUndefined(
        query<GrButton>(element, `[data-action-key="${key}"]`)
      );
    });

    test('action button properties', async () => {
      const key = changeActions.add(ActionType.REVISION, 'Bork!');
      await element.updateComplete;
      const button = queryAndAssert<GrButton>(
        element,
        `[data-action-key="${key}"]`
      );
      assert.isOk(button);
      assert.equal(button.getAttribute('data-label'), 'Bork!');
      assert.isNotOk(button.disabled);
      changeActions.setLabel(key, 'Yo');
      changeActions.setTitle(key, 'Yo hint');
      changeActions.setEnabled(key, false);
      changeActions.setIcon(key, 'hive');
      await element.updateComplete;
      assert.equal(button.getAttribute('data-label'), 'Yo');
      assert.equal(button.parentElement!.getAttribute('title'), 'Yo hint');
      assert.isTrue(button.disabled);
      assert.equal(queryAndAssert<GrIcon>(button, 'gr-icon').icon, 'hive');
    });

    test('hide action buttons', async () => {
      const key = changeActions.add(ActionType.REVISION, 'Bork!');
      await element.updateComplete;
      let button = query<GrButton>(element, `[data-action-key="${key}"]`);
      assert.isOk(button);
      assert.isFalse(button.hasAttribute('hidden'));
      changeActions.setActionHidden(ActionType.REVISION, key, true);
      await element.updateComplete;
      button = query<GrButton>(element, `[data-action-key="${key}"]`);
      assert.isNotOk(button);
    });

    test('move action button to overflow', async () => {
      const key = changeActions.add(ActionType.REVISION, 'Bork!');
      await element.updateComplete;

      let items = queryAndAssert<GrDropdown>(element, '#moreActions').items;
      assert.isFalse(items?.some(item => item.name === 'Bork!'));
      assert.isOk(query<GrButton>(element, `[data-action-key="${key}"]`));

      changeActions.setActionOverflow(ActionType.REVISION, key, true);
      await element.updateComplete;

      items = queryAndAssert<GrDropdown>(element, '#moreActions').items;
      assert.isTrue(items?.some(item => item.name === 'Bork!'));
      assert.isNotOk(query<GrButton>(element, `[data-action-key="${key}"]`));
    });

    test('change actions priority', async () => {
      const key1 = changeActions.add(ActionType.REVISION, 'Bork!');
      const key2 = changeActions.add(ActionType.CHANGE, 'Squanch?');
      await element.updateComplete;
      let buttons = queryAll<GrButton>(element, '[data-action-key]');
      assert.equal(buttons[0].getAttribute('data-action-key'), key1);
      assert.equal(buttons[1].getAttribute('data-action-key'), key2);
      changeActions.setActionPriority(
        ActionType.REVISION,
        key1,
        ActionPriority.PRIMARY
      );
      await element.updateComplete;
      buttons = queryAll<GrButton>(element, '[data-action-key]');
      assert.equal(buttons[0].getAttribute('data-action-key'), key2);
      assert.equal(buttons[1].getAttribute('data-action-key'), key1);
    });
  });
});
