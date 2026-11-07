import { parse, stringify, isLosslessNumber } from 'lossless-json';
// Preserve wire integers before JSON.parse could round them. No raw device JSON enters templates.
export function decode<T>(text: string): T {
  function convert(value: unknown): unknown {
    if (isLosslessNumber(value)) {
      const source = value.toString();
      if (/^-?\d+$/.test(source)) {
        const exact = BigInt(source);
        return exact <= BigInt(Number.MAX_SAFE_INTEGER) && exact >= BigInt(Number.MIN_SAFE_INTEGER) ? Number(exact) : exact;
      }
      const n = Number(source);
      if (!Number.isFinite(n)) throw new Error('非法数值');
      return n;
    }
    if (Array.isArray(value)) return value.map(convert);
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, convert(v)]));
    return value;
  }
  return convert(parse(text)) as T;
}
export function encode(value: unknown): string { return stringify(value) ?? 'null'; }
