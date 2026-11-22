import { stringifyJson } from './api.js';

const outcomes = { SUCCESS: 'Успех', REJECTED: 'Отказ', ERROR: 'Ошибка' };
const reasons = {
  ACCESS_DENIED: 'Недостаточно прав',
  CONDITION_NOT_MET: 'Условия перехода не выполнены',
  PROCESS_ALREADY_STARTED: 'Процесс уже запущен',
  PROCESS_NOT_STARTED: 'Процесс ещё не запущен',
  NO_EXACT_TEMPLATE: 'У класса нет собственного шаблона',
  FINAL_STATE: 'Процесс завершён',
  INVALID_ACTION: 'Действие недоступно',
  INVALID_COMMAND: 'Некорректная команда',
  AMBIGUOUS_TRANSITION: 'Подходит несколько переходов',
  UNKNOWN_USER: 'Неизвестный пользователь',
  UNKNOWN_OBJECT: 'Неизвестный объект'
};
const timeFormat = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const dateFormat = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' });

function item(tag, text = '', className = '') {
  const node = document.createElement(tag);
  node.className = className;
  node.textContent = text;
  return node;
}

export function renderCommandJournal(list, records, { process, users = [] } = {}) {
  const states = new Map(process?.states.map(state => [state.id, state.title || state.id]));
  const actions = new Map(process?.transitions.map(transition => [transition.action, transition.actionTitle || transition.action]));
  const names = new Map(users.map(user => [user.id, user.name]));
  const stateTitle = value => value == null ? '—' : states.get(value) || value;
  list.replaceChildren(...records.map(entry => {
    const row = item('li', '', 'history-entry');
    row.dataset.entryId = entry.id;
    row.dataset.outcome = entry.outcome;
    const when = item('time', '', 'history-when');
    const date = new Date(entry.timestamp);
    if (!Number.isNaN(date.getTime())) {
      when.dateTime = entry.timestamp;
      when.title = entry.timestamp;
      when.append(item('span', timeFormat.format(date), 'history-clock'), item('span', dateFormat.format(date), 'history-date'));
    }
    const body = item('div', '', 'history-content');
    const meta = item('p', '', 'history-event-meta');
    meta.append(item('span', outcomes[entry.outcome] || entry.outcome, 'history-operation command-outcome'),
      item('span', names.get(entry.userId) || entry.userId || 'Пользователь не указан', 'history-user'),
      item('span', entry.objectId || 'Заказ не указан', 'history-user'));
    const title = entry.kind === 'START' ? 'Запуск процесса' : actions.get(entry.actionId) || entry.actionId || 'Команда';
    body.append(meta, item('h3', title, 'history-event-title'),
      item('p', stateTitle(entry.before) + ' → ' + stateTitle(entry.after), 'history-description'));
    if (entry.reason) body.append(item('p',
      reasons[entry.reason] ? reasons[entry.reason] + ' (' + entry.reason + ')' : entry.reason, 'history-description command-reason'));
    if (entry.explanation) body.append(item('p', entry.explanation, 'history-description command-reason'));
    if (entry.command != null) {
      const details = item('details', '', 'history-snapshot');
      details.append(item('summary', 'Исходная команда'), item('pre',
        typeof entry.command === 'string' ? entry.command : stringifyJson(entry.command), 'command-json'));
      body.append(details);
    }
    row.append(when, body);
    return row;
  }));
}
