/**
 * @license
 * Copyright 2015 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import '../../shared/gr-cursor-manager/gr-cursor-manager';
import '../gr-change-list-item/gr-change-list-item';
import '../gr-change-list-section/gr-change-list-section';
import {GrChangeListItem} from '../gr-change-list-item/gr-change-list-item';
import '../../plugins/gr-endpoint-decorator/gr-endpoint-decorator';
import {navigationToken} from '../../core/gr-navigation/gr-navigation';
import {GrCursorManager} from '../../shared/gr-cursor-manager/gr-cursor-manager';
import {
  AccountInfo,
  ChangeInfo,
  PreferencesInput,
  ServerInfo,
  UserId,
} from '../../../types/common';
import {fire, fireReload} from '../../../utils/event-util';
import {ColumnNames, ScrollMode} from '../../../constants/constants';
import {
  getRequirements,
  orderSubmitRequirementNames,
} from '../../../utils/label-util';
import {Key} from '../../../utils/dom-util';
import {assertIsDefined, unique} from '../../../utils/common-util';
import {changeListStyles} from '../../../styles/gr-change-list-styles';
import {fontStyles} from '../../../styles/gr-font-styles';
import {sharedStyles} from '../../../styles/shared-styles';
import {css, html, LitElement, nothing, PropertyValues} from 'lit';
import {customElement, property, state} from 'lit/decorators.js';
import {Shortcut, ShortcutController} from '../../lit/shortcut-controller';
import {queryAll} from '../../../utils/common-util';
import {GrChangeListSection} from '../gr-change-list-section/gr-change-list-section';
import {ValueChangedEvent} from '../../../types/events';
import {resolve} from '../../../models/dependency';
import {createChangeUrl} from '../../../models/views/change';
import {pluginLoaderToken} from '../../shared/gr-js-api-interface/gr-plugin-loader';
import {subscribe} from '../../lit/subscription-controller';
import {
  changeTablePrefs,
  userModelToken,
} from '../../../models/user/user-model';
import {configModelToken} from '../../../models/config/config-model';

export interface ChangeListSection {
  countLabel?: string;
  emptyStateSlotName?: string;
  name?: string;
  query?: string;
  results: ChangeInfo[];
}

/**
 * Calculate the relative index of the currently selected change wrt to the
 * section it belongs to.
 * The 10th change in the overall list may be the 4th change in it's section
 * so this method maps 10 to 4.
 * selectedIndex contains the index of the change wrt the entire change list.
 * Private but used in test
 *
 */
export function computeRelativeIndex(
  selectedIndex?: number,
  sectionIndex?: number,
  sections?: ChangeListSection[]
) {
  if (
    selectedIndex === undefined ||
    sectionIndex === undefined ||
    sections === undefined
  )
    return;
  for (let i = 0; i < sectionIndex; i++)
    selectedIndex -= sections[i].results.length;
  if (selectedIndex < 0) return; // selected change lies in previous sections

  // the selectedIndex lies in the current section
  if (selectedIndex < sections[sectionIndex].results.length)
    return selectedIndex;
  return; // selected change lies in future sections
}

@customElement('gr-change-list')
export class GrChangeList extends LitElement {
  /**
   * The logged-in user's account, or an empty object if no user is logged
   * in.
   */
  @property({type: Object})
  loggedInUser?: AccountInfo;

  /**
   * When the list is part of the dashboard, the user for which the dashboard is
   * generated.
   */
  @property({type: String})
  dashboardUser?: UserId | 'self';

  @property({type: Array})
  changes?: ChangeInfo[];

  /**
   * ChangeInfo objects grouped into arrays. The sections and changes
   * properties should not be used together.
   */
  @property({type: Array})
  sections?: ChangeListSection[] = [];

  @state() private dynamicHeaderEndpoints?: string[];

  @property({type: Number}) selectedIndex = 0;

  @property({type: Boolean})
  showNumber?: boolean; // No default value to prevent flickering.

  @property({type: Boolean})
  showReviewedState = false;

  @property({type: Array})
  changeTableColumns?: string[];

  @property({type: String})
  usp?: string;

  @property({type: Array})
  visibleChangeTableColumns?: string[];

  @state()
  preferences?: PreferencesInput;

  @property({type: Boolean})
  isCursorMoving = false;

  // private but used in test
  @state() config?: ServerInfo;

  private readonly shortcuts = new ShortcutController(this);

  private readonly getConfigModel = resolve(this, configModelToken);

  private readonly getPluginLoader = resolve(this, pluginLoaderToken);

  private readonly getNavigation = resolve(this, navigationToken);

  private readonly getUserModel = resolve(this, userModelToken);

  private cursor = new GrCursorManager();

  constructor() {
    super();
    this.cursor.scrollMode = ScrollMode.KEEP_VISIBLE;
    this.cursor.focusOnMove = true;
    this.shortcuts.addAbstract(Shortcut.CURSOR_NEXT_CHANGE, () =>
      this.nextChange()
    );
    this.shortcuts.addAbstract(Shortcut.CURSOR_PREV_CHANGE, () =>
      this.prevChange()
    );
    this.shortcuts.addAbstract(Shortcut.NEXT_PAGE, () => this.nextPage());
    this.shortcuts.addAbstract(Shortcut.PREV_PAGE, () => this.prevPage());
    this.shortcuts.addAbstract(Shortcut.OPEN_CHANGE, () => this.openChange());
    this.shortcuts.addAbstract(Shortcut.TOGGLE_CHANGE_STAR, () =>
      this.toggleChangeStar()
    );
    this.shortcuts.addAbstract(Shortcut.REFRESH_CHANGE_LIST, () =>
      this.refreshChangeList()
    );
    this.shortcuts.addAbstract(Shortcut.TOGGLE_CHECKBOX, () =>
      this.toggleCheckbox()
    );
    this.shortcuts.addGlobal({key: Key.ENTER}, () => this.openChange());
    subscribe(
      this,
      () => this.getUserModel().preferences$,
      x => (this.preferences = x)
    );
    subscribe(
      this,
      () => this.getConfigModel().serverConfig$,
      config => {
        this.config = config;
      }
    );
  }

  override connectedCallback() {
    super.connectedCallback();
    this.getPluginLoader()
      .awaitPluginsLoaded()
      .then(() => {
        this.dynamicHeaderEndpoints =
          this.getPluginLoader().pluginEndPoints.getDynamicEndpoints(
            'change-list-header'
          );
      });
  }

  override disconnectedCallback() {
    this.cursor.unsetCursor();
    super.disconnectedCallback();
  }

  static override get styles() {
    return [
      changeListStyles,
      fontStyles,
      sharedStyles,
      css`
        :host {
          display: block;
          padding: 0 8px;
          box-sizing: border-box;
        }
        #changeList {
          border-collapse: separate;
          width: 100%;
        }
        .section-count-label {
          color: var(--deemphasized-text-color);
          font-family: var(--font-family);
          font-size: var(--font-size-small);
          font-weight: var(--font-weight-normal);
          line-height: var(--line-height-small);
        }
        a.section-title:hover {
          text-decoration: none;
        }
        a.section-title:hover .section-count-label {
          text-decoration: none;
        }
        a.section-title:hover .section-name {
          text-decoration: underline;
        }
      `,
    ];
  }

  override render() {
    if (!this.sections) return;
    const labelNames = this.computeLabelNames(this.sections);
    const startIndices = this.calculateStartIndices(this.sections);
    return html`
      <table id="changeList">
        ${this.sections.map((changeSection, sectionIndex) =>
          this.renderSection(
            changeSection,
            sectionIndex,
            labelNames,
            startIndices[sectionIndex]
          )
        )}
      </table>
    `;
  }

  private calculateStartIndices(sections: ChangeListSection[]): number[] {
    const startIndices = Array.from<number>({length: sections.length}).fill(0);
    for (let i = 1; i < sections.length; ++i) {
      startIndices[i] = startIndices[i - 1] + sections[i - 1].results.length;
    }
    return startIndices;
  }

  private renderSection(
    changeSection: ChangeListSection,
    sectionIndex: number,
    labelNames: string[],
    startIndex: number
  ) {
    return html`
      <gr-change-list-section
        .changeSection=${changeSection}
        .labelNames=${labelNames}
        .dynamicHeaderEndpoints=${this.dynamicHeaderEndpoints}
        .isCursorMoving=${this.isCursorMoving}
        .loggedInUser=${this.loggedInUser}
        .dashboardUser=${this.dashboardUser}
        .selectedIndex=${computeRelativeIndex(
          this.selectedIndex,
          sectionIndex,
          this.sections
        )}
        .showNumber=${this.showNumber}
        .visibleChangeTableColumns=${this.visibleChangeTableColumns}
        .usp=${this.usp}
        .startIndex=${startIndex}
        .triggerSelectionCallback=${(index: number) => {
          this.selectedIndex = index;
          this.cursor.setCursorAtIndex(this.selectedIndex);
        }}
      >
        ${changeSection.emptyStateSlotName
          ? html`<slot
              slot=${changeSection.emptyStateSlotName}
              name=${changeSection.emptyStateSlotName}
            ></slot>`
          : nothing}
      </gr-change-list-section>
    `;
  }

  override willUpdate(changedProperties: PropertyValues) {
    if (
      changedProperties.has('loggedInUser') ||
      changedProperties.has('preferences') ||
      changedProperties.has('config') ||
      changedProperties.has('sections')
    ) {
      this.computeVisibleChangeTableColumns();
    }

    if (changedProperties.has('changes')) {
      this.changesChanged();
    }
  }

  override updated(changedProperties: PropertyValues) {
    if (changedProperties.has('sections')) {
      this.sectionsChanged();
    }
    if (changedProperties.has('selectedIndex')) {
      fire(this, 'selected-index-changed', {
        value: this.selectedIndex ?? 0,
      });
    }
  }

  private toggleCheckbox() {
    assertIsDefined(this.selectedIndex, 'selectedIndex');
    let selectedIndex = this.selectedIndex;
    assertIsDefined(this.sections, 'sections');
    const changeSections = queryAll<GrChangeListSection>(
      this,
      'gr-change-list-section'
    );
    for (let i = 0; i < this.sections.length; i++) {
      if (selectedIndex >= this.sections[i].results.length) {
        selectedIndex -= this.sections[i].results.length;
        continue;
      }
      changeSections[i].toggleChange(selectedIndex);
      return;
    }
    throw new Error('invalid selected index');
  }

  private computeVisibleChangeTableColumns() {
    if (!this.config) return;

    this.changeTableColumns = Object.values(ColumnNames);
    this.showNumber = false;
    this.visibleChangeTableColumns = Object.values(ColumnNames);
    if (this.loggedInUser && this.preferences) {
      this.showNumber = !!this.preferences?.legacycid_in_change_table;
      const prefColumns = changeTablePrefs(this.preferences);
      // This is for sorting `prefColumns` as in `ColumnNames`:
      this.visibleChangeTableColumns = Object.values(ColumnNames).filter(col =>
        prefColumns.includes(col)
      );
    }
  }

  // private but used in test
  computeLabelNames(sections: ChangeListSection[]) {
    if (!sections) return [];
    if (this.config?.submit_requirement_dashboard_columns?.length) {
      return this.config?.submit_requirement_dashboard_columns;
    }
    const changes = sections.map(section => section.results).flat();
    let labels: string[] = [];
    if (this.config?.dashboard_show_all_labels) {
      labels = changes.map(change => Object.keys(change.labels ?? {})).flat();
    } else {
      labels = changes
        .map(change => getRequirements(change))
        .flat()
        .map(requirement => requirement.name);
    }
    return orderSubmitRequirementNames(labels.filter(unique));
  }

  private changesChanged() {
    this.sections = this.changes ? [{results: this.changes}] : [];
  }

  private nextChange() {
    this.isCursorMoving = true;
    this.cursor.next();
    this.isCursorMoving = false;
    this.selectedIndex = this.cursor.index;
  }

  private prevChange() {
    this.isCursorMoving = true;
    this.cursor.previous();
    this.isCursorMoving = false;
    this.selectedIndex = this.cursor.index;
  }

  private async openChange() {
    const change = await this.changeForIndex(this.selectedIndex);
    if (change) this.getNavigation().setUrl(createChangeUrl({change}));
  }

  private nextPage() {
    fire(this, 'next-page', {});
  }

  private prevPage() {
    fire(this, 'previous-page', {});
  }

  private refreshChangeList() {
    fireReload(this);
  }

  private toggleChangeStar() {
    this.toggleStarForIndex(this.selectedIndex);
  }

  private async toggleStarForIndex(index?: number) {
    const changeEls = await this.getListItems();
    if (index === undefined || index >= changeEls.length || !changeEls[index]) {
      return;
    }

    const changeEl = changeEls[index];
    const grChangeStar = changeEl?.shadowRoot?.querySelector('gr-change-star');
    if (grChangeStar) grChangeStar.toggleStar();
  }

  private async changeForIndex(index?: number) {
    const changeEls = await this.getListItems();
    if (index !== undefined && index < changeEls.length && changeEls[index]) {
      return changeEls[index].change;
    }
    return null;
  }

  // Private but used in tests
  async getListItems() {
    const items: GrChangeListItem[] = [];
    const sections = queryAll<GrChangeListSection>(
      this,
      'gr-change-list-section'
    );
    await Promise.all(Array.from(sections).map(s => s.updateComplete));
    for (const section of sections) {
      // getListItems() is triggered when sectionsChanged observer is triggered
      // In some cases <gr-change-list-item> has not been attached to the DOM
      // yet and hence queryAll returns []
      // Once the items have been attached, sectionsChanged() is not called
      // again and the cursor stops are not updated to have the correct value
      // hence wait for section to render before querying for items
      const res = queryAll<GrChangeListItem>(section, 'gr-change-list-item');
      items.push(...res);
    }
    return items;
  }

  // Private but used in tests
  async sectionsChanged() {
    // Ensure we have a valid array for cursor.stops even if getListItems() returns undefined
    // This prevents TypeError when forEach is called on cursor.stops
    const items = await this.getListItems();
    this.cursor.stops = items ?? [];
    this.cursor.moveToStart();
    if (this.selectedIndex) this.cursor.setCursorAtIndex(this.selectedIndex);
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'gr-change-list': GrChangeList;
  }
  interface HTMLElementEventMap {
    'selected-index-changed': ValueChangedEvent<number>;
    /** Fired when next page key shortcut was pressed. */
    'next-page': CustomEvent<{}>;
    /** Fired when previous page key shortcut was pressed. */
    'previous-page': CustomEvent<{}>;
  }
}
