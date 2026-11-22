const api = '/api/coordination';
const processes = document.querySelector('#processes');
const journal = document.querySelector('#journal');
const journalScroll = document.querySelector('#journal-scroll');
const refresh = document.querySelector('#refresh');
const resultMessage = document.querySelector('#result');
const outcomes = {SUCCESS: 'Успех', REJECTED: 'Отказ', ERROR: 'Ошибка'};
let busy = false;
let highlighted = new Set();

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text != null) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function showResult(text, kind = 'success') {
  resultMessage.textContent = text;
  resultMessage.dataset.kind = kind;
  resultMessage.classList.toggle('notice-error', kind === 'error');
}

function setBusy(value) {
  busy = value;
  refresh.disabled = value;
  processes.querySelectorAll('button').forEach(button => { button.disabled = value; });
}

function stateChange(entry) {
  return `${entry.before ?? '—'} → ${entry.after ?? '—'}`;
}

function processCard(process) {
  const card = element('article', null, 'detail-panel process-card');
  card.id = `process-${process.id}`;
  const header = element('header');
  const heading = element('div');
  heading.append(element('h2', process.title), element('p', `${process.classTitle} · ${process.id}`, 'object-label'));
  const state = process.states.find(stage => stage.id === process.stateId);
  const status = element('span', state?.title ?? 'Процесс не запущен',
    state ? 'status-badge' : 'status-badge pending');
  status.dataset.state = process.stateId ?? '';
  if (state) status.title = state.id;
  header.append(heading, status);
  card.append(header);

  const stages = element('ul', null, 'stages');
  stages.setAttribute('aria-label', 'Состояния процесса');
  for (const stage of process.states) {
    const item = element('li', stage.id);
    item.title = stage.title;
    if (stage.id === process.stateId) item.setAttribute('aria-current', 'step');
    stages.append(item);
  }
  card.append(stages, element('p', `Последнее успешное действие: ${process.lastActionId ?? '—'}`, 'last-action'));
  for (const [name, targets] of Object.entries(process.links)) {
    card.append(element('p', `Связь ${name} → ${targets.join(', ') || '—'}`, 'process-links'));
  }

  const controls = element('div', null, 'process-controls');
  if (process.operations.length) {
    controls.append(element('p', `От имени: ${process.operations[0].userName}`));
    const actions = element('div', null, 'process-actions');
    for (const [index, operation] of process.operations.entries()) {
      const button = element('button', operation.title, index === 0 ? 'button button-primary' : 'button');
      button.type = 'button';
      button.disabled = busy;
      button.dataset.action = operation.id ?? operation.type;
      button.addEventListener('click', () => send(process.id, operation));
      actions.append(button);
    }
    controls.append(actions);
  } else {
    controls.append(element('p', state?.terminal ? 'Процесс завершён.' : 'Нет доступных действий.'));
  }
  card.append(controls);
  return card;
}

function journalRow(entry) {
  const row = element('tr');
  row.id = `entry-${entry.id}`;
  row.dataset.kind = entry.kind;
  if (highlighted.has(entry.id)) row.dataset.related = 'true';

  const operation = element('td');
  operation.append(element('strong', [entry.kind, entry.eventName ?? entry.actionId].filter(Boolean).join(' ')));
  if (entry.userId) operation.append(element('span', entry.userId, 'row-secondary'));
  const object = element('td', entry.objectId);
  if (entry.sourceId) object.append(element('span', `${entry.sourceId} → ${entry.recipientId}`, 'row-secondary'));

  const outcome = element('td');
  outcome.dataset.outcome = entry.outcome;
  outcome.append(element('strong', outcomes[entry.outcome] ?? entry.outcome));
  if (entry.reason) outcome.append(element('code', entry.reason, 'row-secondary'));

  const correlation = element('td');
  const id = element('code', `id ${entry.id.slice(0, 8)}…`);
  id.title = entry.id;
  correlation.append(id);
  if (entry.parentId) {
    const parent = element('a', `parentId ${entry.parentId.slice(0, 8)}…`, 'row-secondary');
    parent.href = `#entry-${entry.parentId}`;
    parent.title = entry.parentId;
    correlation.append(parent);
  }
  row.append(operation, object, element('td', stateChange(entry)), outcome, correlation);
  return row;
}

async function load(scrollToLatest = false) {
  const response = await fetch(api, {cache: 'no-store'});
  if (!response.ok) throw new Error('Не удалось прочитать сеанс. Попробуйте обновить данные.');
  const snapshot = await response.json();
  processes.replaceChildren(...snapshot.processes.map(processCard));
  journal.replaceChildren(...snapshot.journal.map(journalRow));
  document.querySelector('#entry-count').textContent = snapshot.journal.length;
  document.querySelector('#empty-journal').hidden = snapshot.journal.length > 0;
  journalScroll.hidden = snapshot.journal.length === 0;
  if (scrollToLatest) journalScroll.scrollTop = journalScroll.scrollHeight;
}

async function send(objectId, operation) {
  if (busy) return;
  setBusy(true);
  try {
    const response = await fetch(`${api}/commands`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({type: operation.type, objectId, userId: operation.userId, actionId: operation.id})
    });
    const {command, deliveries = [], message} = await response.json();
    if (!command) throw new Error(message ?? 'Сервер не вернул результат команды.');
    highlighted = new Set(deliveries.length ? [command.id, ...deliveries.map(entry => entry.id)] : []);
    const messages = [command, ...deliveries].map(entry => {
      const operationId = entry.eventName ?? entry.actionId ?? 'start';
      return `${entry.objectId} · ${operationId} · ${stateChange(entry)}${entry.reason ? ` (${entry.reason})` : ''}`;
    });
    const kind = command.outcome !== 'SUCCESS' ? 'error'
      : deliveries.some(entry => entry.outcome !== 'SUCCESS') ? 'warning' : 'success';
    showResult(messages.join(' / '), kind);
    await load(true);
  } catch (error) {
    showResult(error.message, 'error');
  } finally {
    setBusy(false);
    const card = document.getElementById(`process-${objectId}`);
    (card?.querySelector('button') ?? resultMessage).focus();
  }
}

async function refreshDashboard() {
  setBusy(true);
  try {
    await load();
    showResult('Сеанс готов к работе.');
  } catch (error) {
    showResult(error.message, 'error');
  } finally {
    setBusy(false);
  }
}

resultMessage.tabIndex = -1;
refresh.addEventListener('click', refreshDashboard);
refreshDashboard();
