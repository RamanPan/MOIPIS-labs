const NUMBER_TOKEN = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/;

// Preserve the exact JSON number, including decimals and integers beyond 2^53.
export class JsonNumber {
  constructor(token) {
    if (!NUMBER_TOKEN.test(token)) throw new Error('Введите корректное число.');
    this.token = token;
    Object.freeze(this);
  }
  toString() { return this.token; }
}

function parseJson(text) {
  const numbers = [];
  let transformed = '';
  for (let i = 0; i < text.length;) {
    if (text[i] === '"') {
      const start = i++;
      while (i < text.length) {
        if (text[i] === '\\') { i += 2; continue; }
        if (text[i++] === '"') break;
      }
      transformed += text.slice(start, i);
    } else if (text[i] === '-' || /\d/.test(text[i])) {
      const token = text.slice(i).match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/);
      if (!token) throw new Error('Некорректный JSON.');
      const index = numbers.push(new JsonNumber(token[0])) - 1;
      transformed += '{"$workflowNumericToken":' + index + '}';
      i += token[0].length;
    } else {
      transformed += text[i++];
    }
  }
  return JSON.parse(transformed, (key, value) => {
    if (value && typeof value === 'object' && Object.keys(value).length === 1
        && typeof value.$workflowNumericToken === 'number') {
      return numbers[value.$workflowNumericToken];
    }
    return value;
  });
}

function stringifyJson(value) {
  if (value instanceof JsonNumber) return value.token;
  if (Array.isArray(value)) return '[' + value.map(stringifyJson).join(',') + ']';
  if (value !== null && typeof value === 'object') {
    return '{' + Object.entries(value).map(([key, entry]) => JSON.stringify(key) + ':' + stringifyJson(entry)).join(',') + '}';
  }
  return JSON.stringify(value);
}

export class ApiError extends Error {
  constructor(message, code, status) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
  }
}

async function request(path, { signal, body, method } = {}) {
  let response;
  try {
    response = await fetch('/api' + path, {
      method: method || (body === undefined ? 'GET' : 'POST'), signal, cache: 'no-store', credentials: 'same-origin',
      headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: stringifyJson(body) })
    });
  } catch (error) {
    if (error.name === 'AbortError') throw error;
    throw new ApiError(body === undefined
      ? 'Не удалось связаться с сервером. Проверьте подключение и повторите загрузку.'
      : 'Ответ сервера не получен. Операция могла сохраниться. Обновите данные перед повторной отправкой.', 'NETWORK_ERROR', 0);
  }
  let data;
  try { data = parseJson(await response.text()); }
  catch { throw new ApiError('Сервер вернул некорректный ответ.', 'INVALID_RESPONSE', response.status); }
  if (!response.ok) throw new ApiError(data.message || 'Не удалось выполнить запрос.', data.code || 'HTTP_ERROR', response.status);
  return data;
}

export const api = {
  users: () => request('/users'),
  objects: (signal) => request('/objects', { signal }),
  classes: (signal) => request('/classes', { signal }),
  parameters: (classId, signal) => request('/classes/' + encodeURIComponent(classId) + '/parameters', { signal }),
  object: (id, signal) => request('/objects/' + encodeURIComponent(id), { signal }),
  createObject: (body) => request('/objects', { body }),
  process: (id, signal) => request('/objects/' + encodeURIComponent(id) + '/process', { signal }),
  access: (id, userId, signal) => request('/objects/' + encodeURIComponent(id) + '/access?userId=' + encodeURIComponent(userId), { signal }),
  history: (id, signal) => request('/objects/' + encodeURIComponent(id) + '/history', { signal }),
  performAction: (id, userId, actionId) => request('/objects/' + encodeURIComponent(id) + '/actions', { body: { userId, actionId } }),
  updateParameter: (id, userId, parameterId, value) => request('/objects/' + encodeURIComponent(id) + '/parameters/' + encodeURIComponent(parameterId),
    { method: 'PATCH', body: { userId, value } })
};
