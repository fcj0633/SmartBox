import { decode, encode } from './json';
import type { Envelope } from './types';
export class ApiError extends Error {
  constructor(public status: number, public requestId = '') {
    super(({ 400: '请求参数不符合要求', 401: '登录信息无效或已过期', 403: '无权访问此资源', 404: '资源不存在', 409: '请求内容与已有操作冲突', 503: '服务暂不可用' } as Record<number,string>)[status] ?? '暂时无法连接服务');
  }
}
export class Http {
  token = '';
  onUnauthorized: () => void = () => {};
  async request<T>(path: string, options: { method?: string; body?: unknown; signal?: AbortSignal; key?: string; login?: boolean } = {}): Promise<T> {
    const headers: Record<string,string> = {};
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    if (options.key) headers['Idempotency-Key'] = options.key;
    let response: Response;
    const tokenAtStart = this.token;
    try { response = await fetch('/api/v1' + path, { method: options.method ?? 'GET', headers, body: options.body === undefined ? undefined : encode(options.body), signal: options.signal, credentials: 'omit' }); }
    catch (error) { if ((error as Error).name === 'AbortError') throw error; throw new ApiError(0); }
    const body = decode<Envelope<T>>(await response.text());
    if (!response.ok) {
      if (response.status === 401 && !options.login && tokenAtStart === this.token) this.onUnauthorized();
      throw new ApiError(response.status, body.request_id);
    }
    return body.data;
  }
}
