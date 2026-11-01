import { api } from './api.js';
import { parameterValue } from './parameter-input.js';
import { OrderDetail } from './order-detail.js';

const $ = (id) => document.getElementById(id);
const state = { users: [], userId: '', orders: [], drafts: new Map(), fields: new Map(), shownClass: '', parametersReady: false, submitting: false };
const dateFormat = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' });
let ordersRequest, classesRequest, parametersRequest;
let ordersVersion = 0, parametersVersion = 0, classesVersion = 0;

function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}
function option(value, title) { const item = node('option', '', title); item.value = value; return item; }
function selectedUser() { return state.users.find((user) => user.id === state.userId); }
function showNotice(message) { $('notice').textContent = message; $('notice').hidden = false; }
function errorText(error) { return error.message || 'Не удалось выполнить операцию. Попробуйте снова.'; }
const detail = new OrderDetail({ user: selectedUser, onChanged: loadOrders });

async function loadUsers() {
  $('users-error').hidden = true;
  try {
    const users = await api.users();
    state.users = users;
    if (!users.some((user) => user.id === state.userId)) state.userId = users[0]?.id || '';
    $('current-user').replaceChildren(...users.map((user) => option(user.id, user.name || user.id)));
    if (!users.length) $('current-user').append(option('', 'Пользователи не найдены'));
    $('current-user').value = state.userId;
    $('current-user').disabled = !users.length;
    renderUser();
  } catch (error) {
    $('users-error-text').textContent = errorText(error);
    $('users-error').hidden = false;
    if (!state.users.length) {
      $('current-user').replaceChildren(option('', 'Нет соединения с сервером'));
      $('current-roles').textContent = 'Не удалось загрузить пользователей';
    }
  }
}

function renderUser() {
  const user = selectedUser();
  $('current-roles').textContent = user?.roles?.length
    ? user.roles.map((id) => user.roleTitles?.[id] || id).join(' · ') : 'Роли не назначены';
  $('creation-user').textContent = user?.name || 'Пользователь не выбран';
  $('new-order').disabled = !user;
  updateSubmit();
  detail.userChanged();
}

async function loadOrders() {
  ordersRequest?.abort();
  ordersRequest = new AbortController();
  const version = ++ordersVersion;
  $('orders-loading').hidden = false;
  $('orders-grid').hidden = true;
  $('orders-error').hidden = true;
  $('orders-empty').hidden = true;
  $('results-caption').textContent = 'Загрузка заказов…';
  $('refresh-orders').disabled = true;
  try {
    const orders = await api.objects(ordersRequest.signal);
    if (version !== ordersVersion) return;
    state.orders = orders;
    $('total-orders').textContent = orders.length;
    $('total-classes').textContent = new Set(orders.map((order) => order.classId)).size;
    $('total-states').textContent = new Set(orders.map((order) => order.stateId)).size;
    $('orders-count').textContent = orders.length;
    const previous = $('state-filter').value;
    const states = new Map(orders.map((order) => [order.stateId, order.stateTitle || order.stateId]));
    $('state-filter').replaceChildren(option('', 'Все состояния'), ...Array.from(states, ([id, title]) => option(id, title)));
    $('state-filter').value = states.has(previous) ? previous : '';
    renderOrders();
  } catch (error) {
    if (error.name === 'AbortError' || version !== ordersVersion) return;
    $('orders-error-text').textContent = errorText(error);
    $('orders-error').hidden = false;
    $('results-caption').textContent = 'Данные недоступны';
    for (const id of ['total-orders', 'total-classes', 'total-states', 'orders-count']) $(id).textContent = '—';
  } finally {
    if (version === ordersVersion) { $('orders-loading').hidden = true; $('refresh-orders').disabled = false; }
  }
}

function renderOrders() {
  const query = $('order-search').value.trim().toLocaleLowerCase('ru');
  const filter = $('state-filter').value;
  const orders = state.orders.filter((order) => (!filter || order.stateId === filter)
    && [order.orderNumber, order.displayTitle].some((value) => (value || '').toLocaleLowerCase('ru').includes(query)));
  $('orders-grid').replaceChildren(...orders.map(orderCard));
  $('orders-grid').hidden = false;
  $('orders-empty').hidden = orders.length > 0;
  $('empty-title').textContent = state.orders.length ? 'Заказы не найдены' : 'Здесь появятся ваши заказы';
  $('empty-description').textContent = state.orders.length
    ? 'Попробуйте изменить поисковый запрос или выбрать другое состояние.' : 'Создайте первый заказ с помощью кнопки «Новый заказ».';
  $('results-caption').textContent = 'Показано ' + orders.length + ' из ' + state.orders.length;
  $('clear-filters').hidden = !query && !filter;
}

function orderCard(order) {
  const card = node('a', 'order-card');
  card.href = '#/orders/' + encodeURIComponent(order.id);
  const top = node('div', 'card-top');
  top.append(node('span', 'order-number', order.orderNumber || 'Без номера'), node('span', 'status-badge', order.stateTitle || order.stateId));
  const description = node('div');
  description.append(node('h3', 'card-title', order.displayTitle || order.id), node('p', 'card-class', order.classTitle || order.classId));
  const footer = node('div', 'card-footer');
  const date = order.updatedAt ? new Date(order.updatedAt) : null;
  footer.append(node('span', '', date && !Number.isNaN(date.getTime()) ? 'Обновлён ' + dateFormat.format(date) : 'Дата обновления не указана'), node('span', 'card-link', 'Открыть ↗'));
  card.append(top, description, footer);
  return card;
}

async function route() {
  const match = location.hash.match(/^#\/orders\/([^/]+)$/);
  $('orders-view').hidden = Boolean(match);
  $('detail-view').hidden = !match;
  $('breadcrumb-current').textContent = match ? 'Карточка заказа' : 'Заказы';
  if (!match) { detail.close(); document.title = 'Заказы · Workflow Manager'; return; }
  try { await detail.open(decodeURIComponent(match[1])); }
  catch { $('detail-error').hidden = false; $('detail-error-text').textContent = 'Некорректная ссылка на заказ.'; }
}

function saveDraft() {
  if (!state.shownClass) return;
  state.drafts.set(state.shownClass, new Map(Array.from(state.fields, ([id, field]) => [id, { mode: field.mode.value, value: field.input.value }])));
}

async function openCreate() {
  if (!selectedUser()) return;
  $('create-error').hidden = true;
  $('create-dialog').showModal();
  renderUser();
  classesRequest?.abort();
  classesRequest = new AbortController();
  const version = ++classesVersion;
  const previous = $('object-class').value;
  $('object-class').disabled = true;
  try {
    const catalog = await api.classes(classesRequest.signal);
    if (version !== classesVersion || !$('create-dialog').open) return;
    const classes = catalog.filter(entry => entry.templateId);
    $('object-class').replaceChildren(option('', 'Выберите класс заказа'), ...classes.map((entry) => option(entry.id, entry.title || entry.id)));
    $('object-class').value = classes.some((entry) => entry.id === previous) ? previous : '';
    await loadParameters();
    if (!classes.length) throw new Error('Нет классов с настроенным процессом для создания заказа.');
  } catch (error) {
    if (error.name !== 'AbortError' && version === classesVersion) {
      $('parameters-error-text').textContent = errorText(error);
      $('parameters-error').hidden = false;
      state.parametersReady = false;
    }
  } finally { if (version === classesVersion) { $('object-class').disabled = false; updateSubmit(); } }
}

async function loadParameters() {
  saveDraft();
  parametersRequest?.abort();
  parametersRequest = new AbortController();
  const version = ++parametersVersion;
  const classId = $('object-class').value;
  state.parametersReady = false;
  state.shownClass = '';
  state.fields.clear();
  $('parameter-fields').replaceChildren();
  $('parameters-error').hidden = true;
  $('parameters-placeholder').hidden = Boolean(classId);
  $('parameters-loading').hidden = !classId;
  updateSubmit();
  if (!classId) return;
  try {
    const definitions = await api.parameters(classId, parametersRequest.signal);
    if (version !== parametersVersion || !$('create-dialog').open) return;
    const draft = state.drafts.get(classId);
    definitions.forEach((definition, index) => renderField(definition, index, draft?.get(definition.id)));
    state.shownClass = classId;
    state.parametersReady = true;
  } catch (error) {
    if (error.name !== 'AbortError' && version === parametersVersion) {
      $('parameters-error-text').textContent = errorText(error);
      $('parameters-error').hidden = false;
    }
  } finally { if (version === parametersVersion) { $('parameters-loading').hidden = true; updateSubmit(); } }
}

function renderField(definition, index, draft) {
  const types = { string: 'Текст', integer: 'Целое число', decimal: 'Число' };
  if (!types[definition.type]) throw new Error('Неподдерживаемый тип параметра: ' + definition.type);
  const field = node('div', 'parameter-field');
  const heading = node('div', 'field-heading');
  const label = node('label', '', definition.title || definition.id);
  label.htmlFor = 'parameter-' + index;
  heading.append(label, node('span', 'field-type', types[definition.type]));
  const input = node('input');
  input.id = label.htmlFor;
  input.dataset.parameterId = definition.id;
  input.type = definition.type === 'string' ? 'text' : 'number';
  if (input.type === 'number') input.step = definition.type === 'integer' ? '1' : 'any';
  input.placeholder = 'Не задано';
  input.value = draft?.value ?? '';
  const mode = node('select', 'parameter-mode');
  mode.setAttribute('aria-label', 'Режим параметра «' + (definition.title || definition.id) + '»');
  mode.append(option('unset', 'Не задавать'), option('value', 'Значение'), option('null', 'Очистить значение'));
  mode.value = draft?.mode || 'unset';
  input.disabled = mode.value === 'null';
  input.addEventListener('input', () => { mode.value = 'value'; });
  mode.addEventListener('change', () => { input.disabled = mode.value === 'null'; });
  field.append(heading, input, mode);
  if (definition.min !== null && definition.min !== undefined || definition.max !== null && definition.max !== undefined) {
    const bounds = [definition.min == null ? '' : 'от ' + definition.min, definition.max == null ? '' : 'до ' + definition.max].filter(Boolean).join(' ');
    const hint = node('p', 'field-hint', 'Диапазон при переходе: ' + bounds);
    hint.id = input.id + '-hint'; input.setAttribute('aria-describedby', hint.id); field.append(hint);
  }
  state.fields.set(definition.id, { input, mode, definition });
  $('parameter-fields').append(field);
}

function parameterValues() {
  const values = Object.create(null);
  for (const [id, { input, mode, definition }] of state.fields) {
    if (mode.value === 'unset') continue;
    if (mode.value === 'null') { values[id] = null; continue; }
    try {
      values[id] = parameterValue(definition.type, input.value);
    } catch (error) {
      input.focus();
      throw new Error((definition.title || id) + ': ' + errorText(error) + ' Для пустого поля выберите «Не задавать».');
    }
  }
  return values;
}

function updateSubmit() {
  $('submit-create').disabled = state.submitting || !state.parametersReady || !selectedUser()
    || !state.shownClass || state.shownClass !== $('object-class').value;
}
function submissionBusy(busy) {
  state.submitting = busy;
  $('close-create').disabled = busy;
  for (const control of $('create-form').querySelectorAll('input,select,button')) control.disabled = busy;
  if (!busy) for (const { input, mode } of state.fields.values()) input.disabled = mode.value === 'null';
  $('submit-create').textContent = busy ? 'Создание заказа…' : 'Создать заказ ↗';
  updateSubmit();
}

async function createOrder(event) {
  event.preventDefault();
  if (state.submitting || !state.parametersReady || !selectedUser()) return;
  $('create-error').hidden = true;
  try {
    const parameters = parameterValues();
    submissionBusy(true);
    const object = await api.createObject({ userId: state.userId, classId: $('object-class').value, parameters });
    state.drafts.clear(); state.fields.clear(); state.shownClass = '';
    submissionBusy(false);
    $('create-dialog').close();
    $('object-class').value = '';
    $('order-search').value = ''; $('state-filter').value = '';
    showNotice('Заказ создан и сохранён.');
    location.hash = '#/orders/' + encodeURIComponent(object.id);
    loadOrders();
  } catch (error) {
    $('create-error-title').textContent = error.code === 'ACCESS_DENIED' ? 'Недостаточно прав для создания заказа' : 'Не удалось создать заказ';
    $('create-error-text').textContent = errorText(error) + (error.code ? ' (' + error.code + ')' : '');
    $('create-error').hidden = false;
  } finally { if (state.submitting) submissionBusy(false); }
}

$('current-user').addEventListener('change', () => { state.userId = $('current-user').value; renderUser(); });
$('new-order').addEventListener('click', openCreate);
$('retry-users').addEventListener('click', loadUsers);
for (const id of ['refresh-orders', 'retry-orders']) $(id).addEventListener('click', loadOrders);
$('order-search').addEventListener('input', renderOrders);
$('state-filter').addEventListener('change', renderOrders);
$('clear-filters').addEventListener('click', () => { $('order-search').value = ''; $('state-filter').value = ''; renderOrders(); });
$('object-class').addEventListener('change', loadParameters);
$('retry-parameters').addEventListener('click', () => $('object-class').options.length <= 1 ? openCreate() : loadParameters());
$('create-form').addEventListener('submit', createOrder);
for (const id of ['close-create', 'cancel-create']) $(id).addEventListener('click', () => { if (!state.submitting) $('create-dialog').close(); });
$('create-dialog').addEventListener('cancel', (event) => { if (state.submitting) event.preventDefault(); });
$('create-dialog').addEventListener('close', () => { saveDraft(); classesRequest?.abort(); parametersRequest?.abort(); ++classesVersion; ++parametersVersion; });
window.addEventListener('hashchange', () => { route(); if (!location.hash.match(/^#\/orders\//)) loadOrders(); });
route();
Promise.allSettled([loadUsers(), loadOrders()]);
