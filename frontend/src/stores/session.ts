import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { Http, ApiError } from '../api/http';
import { decode, encode } from '../api/json';
import { projectBox } from '../api/projection';
import type { Box, User, Node, Operation, Command } from '../api/types';
import { canDiagnose, canCommand, terminal } from '../domain';
import { SerialRead } from '../realtime/serial';
import { Realtime, type ConnectionState } from '../realtime/connection';

export const useSession = defineStore('session', () => {
  const http = new Http();
  const user = ref<User | null>(null), boxes = ref<Box[]>([]), box = ref<Box | null>(null), nodes = ref<Node[]>([]);
  const operation = ref<Operation | null>(null), error = ref(''), operationError = ref('');
  const notice = ref('');
  const busy = ref(false), loading = ref(false), submitting = ref(false);
  const connection = ref<ConnectionState>('disconnected'), lastSync = ref<number | null>(null), synchronized = ref(false);
  const pending = ref<{ box: string; key: string; command: Command } | null>(null);
  let session = 0, scope = 0, realtime: Realtime | undefined;
  let reader: SerialRead | undefined, operationReader: SerialRead | undefined;
  let fallback: ReturnType<typeof setInterval> | undefined, expires: ReturnType<typeof setTimeout> | undefined;
  let identityRead: Promise<boolean> | undefined;
  const identityValid = computed(() => !!user.value && !!http.token);
  function describe(e: unknown) { return e instanceof ApiError ? e.message + (e.requestId ? `（请求号 ${e.requestId}）` : '') : '暂时无法连接服务，请重试'; }
  function stopScope() {
    scope++; realtime?.stop(); realtime = undefined; reader?.stop(); operationReader?.stop();
    clearInterval(fallback); fallback = undefined;
    box.value = null; nodes.value = []; operation.value = null; pending.value = null;
    submitting.value = false; operationError.value = ''; error.value = ''; synchronized.value = false; lastSync.value = null;
    connection.value = 'disconnected';
  }
  function logout(message = '') {
    session++; http.token = ''; stopScope(); user.value = null; boxes.value = [];
    clearTimeout(expires); busy.value = false; loading.value = false; error.value = message; notice.value = message;
  }
  http.onUnauthorized = () => logout('登录已过期，请重新登录');
  async function login(username: string, password: string) {
    logout(); const epoch = session; busy.value = true;
    try {
      const data = await http.request<{ access_token: string; expires_at: number }>('/auth/login', { method: 'POST', body: { username, password }, login: true });
      if (epoch !== session) return;
      http.token = data.access_token;
      const identity = await http.request<User>('/me'); if (epoch !== session) return;
      user.value = identity;
      expires = setTimeout(() => logout('登录已过期，请重新登录'), Math.max(0, data.expires_at - Date.now()));
    } catch (e) { if (epoch === session) { http.token = ''; user.value = null; error.value = describe(e); } }
    finally { if (epoch === session) busy.value = false; }
  }
  async function verifyIdentity(): Promise<boolean> {
    if (identityRead) return identityRead;
    const epoch = session;
    identityRead = (async () => {
      try {
        const current = await http.request<User>('/me');
        if (epoch !== session || !user.value) return false;
        const old = user.value;
        if (current.role !== old.role || [...current.box_ids].sort().join() !== [...old.box_ids].sort().join()) {
          // Re-login after a permission change gives one unambiguous boundary for all caches and forms.
          logout('权限已变化，请重新登录'); return false;
        }
        return true;
      } catch (e) {
        if (epoch !== session || !user.value) return false;
        if (e instanceof ApiError && [401,403].includes(e.status)) { logout('登录或权限已失效，请重新登录'); return false; }
        error.value = describe(e); synchronized.value = false;
        // A server outage cannot prove credentials invalid. Keep bounded retries until identity can be rechecked.
        return true;
      }
    })();
    try { return await identityRead; } finally { identityRead = undefined; }
  }
  function openScope(id?: string) {
    stopScope(); const epoch = scope, account = session;
    if (!user.value) return;
    if (id && !user.value.box_ids.includes(id)) { error.value = '无权访问此资源'; loading.value = false; return; }
    loading.value = true;
    const current = () => epoch === scope && account === session && !!user.value;
    reader = new SerialRead(async signal => {
      try {
        if (id) {
          const result = await http.request<Box>(`/boxes/${encodeURIComponent(id)}/latest`, { signal });
          const diagnostics = canDiagnose(user.value?.role) ? await http.request<Node[]>(`/boxes/${encodeURIComponent(id)}/nodes`, { signal }) : [];
          if (!current()) return;
          // Pick only the allowed fields; never retain raw evidence or arbitrary server JSON in page state.
          box.value = project(result); nodes.value = diagnostics;
        } else {
          const result = await http.request<Box[]>('/boxes', { signal });
          if (!current()) return;
          boxes.value = result.filter(b => user.value!.box_ids.includes(b.box_id)).map(project);
        }
        error.value = ''; lastSync.value = Date.now(); synchronized.value = true;
      } catch (e) {
        if (!current() || signal.aborted) return;
        error.value = describe(e); synchronized.value = false;
        if (e instanceof ApiError && [403, 404].includes(e.status)) { box.value = null; nodes.value = []; boxes.value = []; operation.value = null; pending.value = null; }
      } finally { if (current()) loading.value = false; }
    });
    operationReader = new SerialRead(async signal => {
      const active = operation.value;
      if (!id || !active || !canCommand(user.value?.role, 'sentry')) return;
      try {
        const result = await http.request<Operation>(`/boxes/${encodeURIComponent(id)}/commands/${encodeURIComponent(active.operation_id)}`, { signal });
        if (current() && operation.value?.operation_id === active.operation_id) { operation.value = result; operationError.value = ''; }
      } catch (e) {
        if (!current() || signal.aborted) return;
        operationError.value = describe(e);
        if (e instanceof ApiError && [403,404].includes(e.status)) { operation.value = null; pending.value = null; }
      }
    });
    reader.trigger();
    const subscriptions = id ? [id] : user.value.box_ids;
    if (subscriptions.length) {
      realtime = new Realtime(
        async () => (await http.request<{ ticket: string }>('/ws-ticket', { method: 'POST' })).ticket,
        subscriptions, state => { if (current()) { connection.value = state; if (state !== 'connected') synchronized.value = false; } },
        message => { if (!current()) return; if (message.box_id && !subscriptions.includes(message.box_id)) return;
          if (message.type === 'command_status' && operation.value?.operation_id === message.data?.operation_id) operationReader?.trigger();
          else reader?.trigger();
        },
        async () => { const valid = await verifyIdentity(); if (current()) reader?.trigger(); return current() && valid; },
      );
      void realtime.connect();
    }
    let ticks = 0;
    fallback = setInterval(() => {
      if (!current()) return;
      ticks++;
      if (ticks % 5 === 0) { reader?.trigger(); void verifyIdentity(); }
      if (operation.value && !terminal(operation.value.status) && ticks % 2 === 0) operationReader?.trigger();
    }, 1000);
  }
  function project(source: Box): Box {
    return projectBox(source, user.value!.role);
  }
  function refresh() { reader?.trigger(); }
  function refreshOperation() { operationReader?.trigger(); }
  async function submit(command?: Command) {
    if (submitting.value || !user.value || !box.value) return;
    const epoch = scope, account = session;
    if (command) {
      if (!canCommand(user.value.role, command.cmd)) return;
      operation.value = null;
      pending.value = { box: box.value.box_id, key: crypto.randomUUID(), command: decode<Command>(encode(command)) };
    }
    const request = pending.value; if (!request) return;
    submitting.value = true; operationError.value = '';
    try {
      const result = await http.request<{ operation_id: string }>(`/boxes/${encodeURIComponent(request.box)}/commands`, { method: 'POST', body: request.command, key: request.key });
      if (epoch !== scope || account !== session) return;
      operation.value = { operation_id: result.operation_id, box_id: request.box, status: 'PENDING', nodes: [] };
      pending.value = null; operationReader?.trigger(); reader?.trigger();
    } catch (e) {
      if (epoch !== scope || account !== session) return;
      operationError.value = describe(e);
      if (e instanceof ApiError && e.status >= 400 && e.status < 500) pending.value = null;
    } finally { if (epoch === scope && account === session) submitting.value = false; }
  }
  return { user, boxes, box, nodes, operation, error, notice, operationError, busy, loading, submitting, connection, lastSync, synchronized, pending, identityValid,
    login, logout, openScope, stopScope, refresh, refreshOperation, submit };
});
