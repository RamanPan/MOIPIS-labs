import { JsonNumber } from './api.js';

function wholeNumber(token) {
  const parts = token.match(/^-?(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/);
  const digits = parts[1] + (parts[2] || '');
  if (/^0+$/.test(digits)) return true;
  const trailingZeros = digits.length - digits.replace(/0+$/, '').length;
  return BigInt(parts[3] || '0') >= BigInt((parts[2] || '').length - trailingZeros);
}

// Input syntax only. Business ranges and authorization are checked by the engine.
export function parameterValue(type, text) {
  if (type === 'string') return text;
  if (!['integer', 'decimal'].includes(type)) throw new Error('Неподдерживаемый тип параметра.');
  const token = text.trim().replace(/^(-?)\./, '$10.').replace(/^(-?)0+(?=\d)/, '$1');
  const value = new JsonNumber(token);
  if (type === 'integer' && !wholeNumber(token)) throw new Error('Требуется целое число.');
  return value;
}
