const $ = id => document.getElementById(id);
const clock = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const calendar = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' });
const operationTitles = { CREATE: 'Создание', ACTION: 'Действие', UPDATE_PARAMETER: 'Изменение параметра' };
function node(tag, text = '', className = '') {
  const value = document.createElement(tag);
  value.textContent = text; value.className = className;
  return value;
}
function valueText(value) { return value === undefined ? 'Нет данных' : value === null ? 'Пустое значение' : value === '' ? 'Пустая строка' : String(value); }
function change(label, before, after) {
  const row = node('div');
  row.append(node('dt', label));
  const description = node('dd', '', 'history-change-values');
  if (before !== undefined) {
    description.append(node('span', before, 'history-old-value'));
    const arrow = node('span', '→', 'history-arrow'); arrow.setAttribute('aria-label', 'на');
    description.append(arrow);
  }
  description.append(node('span', after, 'history-new-value'));
  row.append(description);
  return row;
}

/** A projection of confirmed API history, never an optimistic event log. */
export class HistoryTimeline {
  constructor() {
    this.records = [];
    this.loaded = false;
    $('history-timezone').textContent = 'Часовой пояс: ' + Intl.DateTimeFormat().resolvedOptions().timeZone;
  }

  reset() {
    this.records = []; this.loaded = false;
    $('history-list').replaceChildren();
    $('history-count').textContent = '—'; $('history-tab-count').textContent = '—';
  }

  loading() {
    $('history-list').setAttribute('aria-busy', 'true');
    $('history-message').textContent = this.loaded ? 'Обновляем историю…' : 'Загружаем историю…';
    $('history-message').classList.remove('history-error');
    $('history-message').setAttribute('role', 'status');
    $('history-message').hidden = false;
  }

  render(records, { process, parameters = [], failed = false } = {}) {
    $('history-list').setAttribute('aria-busy', 'false');
    if (failed) {
      $('history-message').textContent = this.loaded && this.records.length
        ? 'Не удалось обновить историю. Показаны ранее загруженные записи. Обновите карточку для повторной попытки.'
        : 'История недоступна. Обновите карточку для повторной попытки.';
      $('history-message').classList.add('history-error');
      $('history-message').setAttribute('role', 'alert');
      $('history-message').hidden = false;
      return;
    }
    this.records = records; this.loaded = true;
    const states = new Map((process?.states || []).map(state => [state.id, state.title || state.id]));
    const actions = new Map((process?.transitions || []).map(transition => [transition.action, transition.actionTitle || transition.action]));
    const titles = new Map(parameters.map(parameter => [parameter.id, parameter.title || parameter.id]));
    const expanded = new Set(Array.from($('history-list').querySelectorAll('.history-snapshot[open]'), details => details.closest('.history-entry').dataset.entryId));
    $('history-list').replaceChildren(...records.map(entry => {
      const details = entry.details || {};
      const row = node('li', '', 'history-entry');
      row.dataset.entryId = entry.id;
      row.dataset.operation = entry.operation;
      const time = node('time', '', 'history-when');
      time.dateTime = entry.timestamp; time.title = entry.timestamp;
      const date = new Date(entry.timestamp);
      time.append(node('span', Number.isNaN(date.getTime()) ? entry.timestamp : clock.format(date), 'history-clock'));
      if (!Number.isNaN(date.getTime())) time.append(node('span', calendar.format(date), 'history-date'));
      const body = node('div', '', 'history-content');
      const meta = node('div', '', 'history-event-meta');
      const type = node('span', operationTitles[entry.operation] || entry.operation || 'Тип не указан', 'history-operation');
      type.title = entry.operation || '';
      meta.append(type, node('span', entry.userName || entry.userId || 'Пользователь не указан', 'history-user'));
      const changes = node('dl', '', 'history-changes');
      let title = 'Операция: ' + (entry.operation || 'тип не указан');
      if (entry.operation === 'CREATE') {
        // The number belongs to the creation snapshot, not today's object title.
        const number = details.parameters?.order_number;
        title = 'Создан заказ' + (number == null || number === '' ? '' : ' ' + number);
        if (details.initialState !== undefined) changes.append(change('Начальное состояние', undefined, states.get(details.initialState) || valueText(details.initialState)));
      } else if (entry.operation === 'ACTION') {
        title = 'Выполнено действие';
        changes.append(change('Состояние', states.get(details.oldState) || valueText(details.oldState), states.get(details.newState) || valueText(details.newState)));
        if (details.actionId !== undefined) changes.append(change('Действие перехода', undefined, actions.get(details.actionId) || valueText(details.actionId)));
        if (details.reason !== undefined && details.reason !== null) changes.append(change('Причина', undefined, valueText(details.reason)));
      } else if (entry.operation === 'UPDATE_PARAMETER') {
        title = 'Изменён параметр «' + (titles.get(details.parameterId) || details.parameterId || 'Нет данных') + '»';
        changes.append(change('Значение', valueText(details.oldValue), valueText(details.newValue)));
      }
      body.append(meta, node('h3', title, 'history-event-title'), changes);
      if (entry.operation === 'CREATE' && details.parameters && Object.keys(details.parameters).length) {
        const snapshot = node('details', '', 'history-snapshot');
        snapshot.open = expanded.has(entry.id);
        snapshot.append(node('summary', 'Параметры при создании'));
        const values = node('dl', '', 'history-snapshot-values');
        for (const [id, value] of Object.entries(details.parameters)) {
          const field = node('div'); field.append(node('dt', titles.get(id) || id), node('dd', valueText(value))); values.append(field);
        }
        snapshot.append(values); body.append(snapshot);
      }
      row.append(time, body);
      return row;
    }));
    $('history-count').textContent = records.length; $('history-tab-count').textContent = records.length;
    $('history-message').textContent = 'В истории пока нет операций.';
    $('history-message').classList.remove('history-error');
    $('history-message').setAttribute('role', 'status');
    $('history-message').hidden = records.length > 0;
  }
}
