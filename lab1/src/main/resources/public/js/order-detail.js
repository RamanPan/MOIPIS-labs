import { api } from './api.js';
import { parameterValue } from './parameter-input.js';
import { ProcessGraph } from './graph.js';
import { HistoryTimeline } from './history.js';

const $ = id => document.getElementById(id);
const types = { string: 'Текст', integer: 'Целое число', decimal: 'Число' };
const messages = {
  ACCESS_DENIED: 'Недостаточно прав для этой операции.',
  CONDITION_NOT_MET: 'Условия перехода не выполнены. Проверьте параметры заказа.',
  FINAL_STATE: 'Заказ находится в конечном состоянии. Изменения запрещены.',
  INVALID_ACTION: 'Действие недоступно в текущем состоянии.',
  CONCURRENT_MODIFICATION: 'Заказ изменился. Данные синхронизированы с сервером.',
  TEMPLATE_ERROR: 'Не удалось однозначно выполнить переход процесса.',
  INVALID_PARAMETER_TYPE: 'Значение не соответствует типу параметра.'
};
function element(tag, text, className = '') {
  const node = document.createElement(tag);
  node.textContent = text;
  node.className = className;
  return node;
}
function valueText(value) { return value === undefined ? 'Не задано' : value === null ? 'Пустое значение' : value === '' ? 'Пустая строка' : String(value); }
function errorText(error) { return (messages[error.code] || error.message || 'Не удалось выполнить запрос.')
  + (error.code ? ' ' + (messages[error.code] && error.message ? error.message + ' ' : '') + '(' + error.code + ')' : ''); }

export class OrderDetail {
  constructor({ user, onChanged }) {
    this.user = user; this.onChanged = onChanged;
    this.id = ''; this.userId = ''; this.version = 0;
    this.object = null; this.process = null; this.access = null;
    this.loading = false; this.pending = null; this.edit = null;
    this.accessLoading = false; this.accessVersion = 0;
    this.tab = 'overview'; this.history = []; this.refreshComplete = false;
    this.graph = new ProcessGraph();
    this.timeline = new HistoryTimeline();
    for (const tab of ['overview', 'history']) {
      $('tab-' + tab).addEventListener('click', () => this.selectTab(tab));
      $('tab-' + tab).addEventListener('keydown', event => {
        const target = { ArrowLeft: 'overview', ArrowRight: 'history', Home: 'overview', End: 'history' }[event.key];
        if (target) { event.preventDefault(); this.selectTab(target, true); }
      });
    }
    for (const id of ['refresh-detail', 'retry-detail']) $(id).addEventListener('click', () => this.load());
    for (const id of ['close-edit', 'cancel-edit']) $(id).addEventListener('click', () => { if (!this.pending) $('edit-dialog').close(); });
    $('edit-dialog').addEventListener('cancel', event => { if (this.pending) event.preventDefault(); });
    $('edit-dialog').addEventListener('close', () => { this.edit = null; });
    $('edit-mode').addEventListener('change', () => this.controls());
    $('edit-form').addEventListener('submit', event => { event.preventDefault(); this.saveParameter(); });
  }

  userChanged() {
    const id = this.user()?.id || '';
    this.renderUser();
    if (id === this.userId) return;
    this.userId = id;
    if (!this.pending && $('edit-dialog').open) $('edit-dialog').close();
    if (this.id) this.object && !this.loading ? this.refreshAccess() : this.load();
  }

  renderUser() {
    const user = this.user();
    $('detail-user').textContent = user?.name || 'Пользователь не выбран';
    $('detail-roles').textContent = user?.roles?.length ? user.roles.map(id => user.roleTitles?.[id] || id).join(' · ') : 'Роли не назначены';
  }

  async open(id) {
    const changed = this.id !== id;
    if (changed) { this.close(); this.selectTab('overview'); $('operation-message').hidden = true; }
    this.id = id;
    await this.load({ focus: changed });
  }

  close() {
    this.request?.abort(); ++this.version;
    this.accessRequest?.abort(); ++this.accessVersion;
    this.id = ''; this.object = null; this.access = null; this.process = null;
    this.loading = false; this.accessLoading = false; this.history = [];
    this.timeline.reset();
    this.graph.destroy();
    if ($('edit-dialog').open) $('edit-dialog').close();
  }

  selectTab(tab, focus = false) {
    this.tab = tab;
    for (const name of ['overview', 'history']) {
      const selected = name === tab;
      $('tab-' + name).setAttribute('aria-selected', String(selected));
      $('tab-' + name).tabIndex = selected ? 0 : -1;
      $('order-' + name).hidden = !selected;
    }
    if (tab === 'overview') {
      if (this.process) this.graph.render(this.process, this.history);
    } else this.graph.suspend();
    if (focus) $('tab-' + tab).focus();
  }

  load(options = {}) {
    const key = this.id + '|' + (this.user()?.id || '');
    if (!options.fresh && this.loading && key === this.loadKey) return this.loadTask;
    this.loadKey = key;
    this.loadTask = this.loadData(options);
    return this.loadTask;
  }

  async refreshAccess() {
    if (!this.id || !this.object) return;
    this.accessRequest?.abort(); this.accessRequest = new AbortController();
    const version = ++this.accessVersion, id = this.id, userId = this.user()?.id;
    this.access = null; this.accessLoading = true;
    this.renderActions(); this.renderParameters(); this.controls();
    $('detail-refresh-status').textContent = 'Обновляем права выбранного пользователя…';
    $('detail-refresh-status').hidden = false;
    try {
      const access = userId ? await api.access(id, userId, this.accessRequest.signal) : null;
      if (version !== this.accessVersion || id !== this.id) return;
      if (access && access.stateId !== this.object.stateId) { await this.load(); return; }
      this.access = access;
      $('detail-warning').hidden = Boolean(access);
      if (!access) $('detail-warning').textContent = 'Выберите учебного пользователя.';
    } catch (error) {
      if (version !== this.accessVersion || error.name === 'AbortError') return;
      $('detail-warning').textContent = errorText(error); $('detail-warning').hidden = false;
    } finally {
      if (version === this.accessVersion && id === this.id) {
        this.accessLoading = false; this.renderActions(); this.renderParameters(); this.controls();
        $('detail-refresh-status').hidden = true;
      }
    }
  }

  async loadData({ focus = false } = {}) {
    if (!this.id) return;
    this.accessRequest?.abort(); ++this.accessVersion; this.accessLoading = false;
    this.request?.abort(); this.request = new AbortController();
    const { signal } = this.request;
    const version = ++this.version, id = this.id, userId = this.user()?.id || '';
    this.loading = true; this.access = null; this.refreshComplete = false;
    this.timeline.loading();
    $('detail-refresh-status').textContent = 'Обновляем данные заказа…';
    $('detail-refresh-status').hidden = !this.object;
    $('detail-error').hidden = true;
    $('detail-warning').hidden = true;
    $('detail-loading').hidden = Boolean(this.object);
    $('detail-content').hidden = !this.object;
    $('actions-list').replaceChildren(element('p', 'Проверяем доступные действия…', 'panel-caption'));
    this.controls();
    try {
      let results;
      for (let attempt = 0; attempt < 2; attempt++) {
        results = await Promise.allSettled([api.object(id, signal), api.process(id, signal),
          userId ? api.access(id, userId, signal) : Promise.resolve(null), api.history(id, signal)]);
        if (version !== this.version) return;
        if (results[0].status === 'rejected') throw results[0].reason;
        const object = results[0].value;
        const process = results[1].status === 'fulfilled' ? results[1].value : null;
        const access = results[2].status === 'fulfilled' ? results[2].value : null;
        const mismatch = process && process.currentStateId !== object.stateId || access && access.stateId !== object.stateId;
        if (!mismatch) break;
        if (attempt === 1) throw new Error('Заказ меняется во время загрузки. Обновите карточку ещё раз.');
      }
      this.object = results[0].value;
      this.process = results[1].status === 'fulfilled' ? results[1].value : null;
      this.access = results[2].status === 'fulfilled' ? results[2].value : null;
      const history = results[3].status === 'fulfilled' ? results[3].value : this.history;
      this.history = history;
      this.renderObject();
      this.renderUser();
      $('detail-content').hidden = false;
      if (this.process && this.tab === 'overview') this.graph.render(this.process, history);
      else if (this.process) this.graph.suspend();
      else this.graph.unavailable('Не удалось загрузить схему процесса. Обновите карточку.');
      this.renderActions();
      this.renderParameters();
      this.renderHistory(history, results[3].status === 'rejected');
      this.refreshComplete = results.every(result => result.status === 'fulfilled') && Boolean(this.access);
      const warnings = results.slice(1).filter(result => result.status === 'rejected').map(result => errorText(result.reason));
      if (!userId) warnings.push('Выберите учебного пользователя, чтобы получить доступные действия.');
      $('detail-warning').textContent = warnings.join(' ');
      $('detail-warning').hidden = !warnings.length;
      if (focus) $('detail-title').focus({ preventScroll: true });
    } catch (error) {
      if (version !== this.version || error.name === 'AbortError') return;
      this.access = null;
      $('detail-error-text').textContent = errorText(error);
      $('detail-error').hidden = false;
      $('detail-content').hidden = true;
      this.graph.destroy();
    } finally {
      if (version === this.version) {
        this.loading = false; $('detail-loading').hidden = true; $('detail-refresh-status').hidden = true;
        $('history-list').setAttribute('aria-busy', 'false'); this.controls();
      }
    }
  }

  renderObject() {
    const object = this.object;
    $('detail-title').textContent = object.parameters.product_name || object.parameters.order_number || object.classTitle || object.id;
    $('detail-number').textContent = object.parameters.order_number || 'Заказ без номера';
    $('detail-class').textContent = object.classTitle || object.classId;
    $('detail-state').textContent = object.stateTitle || object.stateId;
    $('detail-id').textContent = object.id;
    $('detail-template').textContent = this.process?.templateTitle || object.templateId;
    $('detail-template-id').textContent = this.process?.templateId || object.templateId;
    document.title = $('detail-title').textContent + ' · Workflow Manager';
  }

  canEdit(id) {
    return Boolean(this.access?.canEditParameters && this.access.editableParameterIds?.includes(id));
  }

  renderParameters() {
    $('detail-parameters').replaceChildren(...this.object.parameterDefinitions.map(definition => {
      const row = element('div', '', 'parameter-row');
      const label = element('dt', definition.title || definition.id);
      label.append(element('span', types[definition.type] || definition.type, 'parameter-type'));
      const value = this.object.parameters[definition.id];
      const content = element('dd', '', 'parameter-content');
      content.append(element('span', valueText(value), value === null || value === undefined ? 'unset-value' : 'parameter-value'));
      if (this.canEdit(definition.id)) {
        const button = element('button', 'Изменить', 'button button-small parameter-edit');
        button.type = 'button'; button.dataset.editParameter = definition.id;
        button.setAttribute('aria-label', 'Изменить параметр «' + (definition.title || definition.id) + '»');
        button.addEventListener('click', () => this.openEdit(definition));
        content.append(button);
      }
      row.append(label, content);
      return row;
    }));
    if (!this.object.parameterDefinitions.length) $('detail-parameters').append(element('p', 'У этого класса нет параметров.', 'panel-caption'));
  }

  renderActions() {
    const actions = new Map((this.access?.availableActions || []).map(action => [action.id, action]));
    $('actions-list').replaceChildren(...Array.from(actions.values(), action => {
      const button = element('button', action.title || action.id, 'button button-primary process-action');
      button.type = 'button'; button.dataset.actionId = action.id;
      button.addEventListener('click', () => this.run(() => api.performAction(this.id, this.user().id, action.id), 'Действие выполнено.',
        { actionId: action.id, loadingMessage: 'Выполняем действие «' + (action.title || action.id) + '». Ожидаем подтверждения сервера…' }));
      return button;
    }));
    if (!actions.size) $('actions-list').append(element('p', this.access
      ? 'Для выбранного пользователя в этом состоянии нет доступных действий.' : 'Доступные действия не получены.', 'panel-caption'));
    $('access-caption').textContent = this.access ? this.access.canEditParameters ? 'Редактирование разрешено' : 'Параметры только для чтения' : 'Доступ не проверен';
  }

  renderHistory(history, failed) {
    this.timeline.render(history, { process: this.process, parameters: this.object.parameterDefinitions, failed });
  }

  controls() {
    const busy = this.loading || this.accessLoading || Boolean(this.pending);
    $('actions-list').setAttribute('aria-busy', String(busy));
    for (const button of document.querySelectorAll('.process-action,.parameter-edit')) button.disabled = busy;
    $('refresh-detail').disabled = busy;
    $('retry-detail').disabled = busy;
    if (this.loading || this.accessLoading) $('access-caption').textContent = 'Проверяем доступ…';
    $('current-user').disabled = Boolean(this.pending) || !this.user();
    const editBusy = Boolean(this.pending);
    $('close-edit').disabled = editBusy;
    $('cancel-edit').disabled = editBusy;
    $('edit-mode').disabled = editBusy;
    $('edit-value').disabled = editBusy || $('edit-mode').value === 'null';
    $('save-parameter').disabled = busy || !this.edit || this.edit.objectId !== this.id || !this.canEdit(this.edit.definition.id);
    $('save-parameter').textContent = editBusy ? 'Сохранение…' : 'Сохранить изменение';
  }

  notify(message, kind) {
    const notice = $('operation-message');
    notice.replaceChildren(element('span', message, 'notification-copy'));
    if (kind !== 'loading') {
      const close = element('button', '×', 'notification-close');
      close.type = 'button'; close.setAttribute('aria-label', 'Закрыть уведомление');
      close.addEventListener('click', () => { notice.hidden = true; }); notice.append(close);
    }
    notice.dataset.kind = kind;
    notice.classList.toggle('notice-error', kind === 'error');
    notice.setAttribute('role', kind === 'error' ? 'alert' : 'status');
    notice.setAttribute('aria-live', kind === 'error' ? 'assertive' : 'polite');
    notice.setAttribute('aria-busy', 'false');
    notice.hidden = false;
  }

  async run(operation, successMessage, { actionId, loadingMessage = 'Сохраняем изменение. Ожидаем подтверждения сервера…' } = {}) {
    if (this.pending || this.loading || this.accessLoading || !this.id || !this.user() || !this.access) return false;
    const id = this.id;
    this.pending = { id };
    this.notify(loadingMessage, 'loading');
    this.controls();
    let failure = null;
    try { await operation(); } catch (error) { failure = error; }
    try {
      this.onChanged();
      if (this.id === id) {
        await this.load({ fresh: true });
        if (this.id === id) {
          this.notify(failure ? errorText(failure) : successMessage
            + (this.refreshComplete ? '' : ' Часть данных не удалось обновить. Обновите карточку.'), failure ? 'error' : 'success');
        }
      }
      return !failure;
    } finally {
      this.pending = null;
      this.controls();
      if (actionId && this.id === id && this.tab === 'overview' && document.activeElement === document.body) {
        const next = Array.from(document.querySelectorAll('.process-action')).find(button => button.dataset.actionId === actionId);
        const target = next || $('actions-title'); target.tabIndex = next ? 0 : -1; target.focus({ preventScroll: true });
      }
      if (failure && this.edit?.objectId === id) {
        $('edit-error').textContent = errorText(failure);
        $('edit-error').hidden = false;
      }
    }
  }

  openEdit(definition) {
    if (this.pending || this.loading || !this.canEdit(definition.id)) return;
    this.edit = { objectId: this.id, definition };
    const value = this.object.parameters[definition.id];
    $('edit-title').textContent = 'Изменение параметра';
    $('edit-label').textContent = definition.title || definition.id;
    $('edit-value').type = definition.type === 'string' ? 'text' : 'number';
    $('edit-value').step = definition.type === 'integer' ? '1' : 'any';
    $('edit-value').value = value === null || value === undefined ? '' : String(value);
    $('edit-mode').value = value === null ? 'null' : 'value';
    const bounds = [definition.min == null ? '' : 'от ' + definition.min, definition.max == null ? '' : 'до ' + definition.max].filter(Boolean).join(' ');
    $('edit-hint').textContent = (types[definition.type] || definition.type) + (bounds ? '. Диапазон при переходе: ' + bounds : '');
    $('edit-error').hidden = true;
    this.controls();
    $('edit-dialog').showModal();
  }

  async saveParameter() {
    if (!this.edit || this.pending || this.loading || !this.canEdit(this.edit.definition.id)) return;
    const { objectId, definition } = this.edit;
    const userId = this.user().id;
    $('edit-error').hidden = true;
    try {
      const value = $('edit-mode').value === 'null' ? null : parameterValue(definition.type, $('edit-value').value);
      const accepted = await this.run(() => api.updateParameter(objectId, userId, definition.id, value), 'Параметр сохранён.');
      if (accepted && this.edit?.objectId === objectId && this.edit.definition.id === definition.id) {
        $('edit-dialog').close();
        const target = Array.from(document.querySelectorAll('.parameter-edit')).find(button => button.dataset.editParameter === definition.id) || $('parameters-title');
        target.tabIndex = 0; target.focus({ preventScroll: true });
      }
    } catch (error) {
      $('edit-error').textContent = errorText(error); $('edit-error').hidden = false;
    }
  }
}
