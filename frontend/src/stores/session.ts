import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { Http, ApiError } from '../api/http';

export interface SessionUser { username: string; role: string; box_ids: string[] }

export const useSession = defineStore('session', () => {
  const http = new Http();
  const businessHome = ref('/enterprise');
  const user = ref<SessionUser | null>(null);
  const busy = ref(false), error = ref(''), notice = ref('');
  const identityValid = computed(() => !!user.value && !!http.token);

  function describe(e: unknown) {
    return e instanceof ApiError
      ? e.message + (e.requestId ? `（请求号 ${e.requestId}）` : '')
      : '暂时无法连接服务，请重试';
  }

  function logout(message = '') {
    http.token = '';
    user.value = null;
    busy.value = false;
    error.value = message;
    notice.value = message;
  }

  http.onUnauthorized = () => logout('登录已过期，请重新登录');

  async function login(username: string, password: string) {
    logout();
    busy.value = true;
    try {
      const data = await http.request<{ access_token: string; expires_at: number }>('/auth/login', {
        method: 'POST',
        body: { username, password },
        login: true,
      });
      http.token = data.access_token;
      user.value = await http.request<SessionUser>('/me');
    } catch (e) {
      http.token = '';
      user.value = null;
      error.value = describe(e);
    } finally {
      busy.value = false;
    }
  }

  return { businessHome, user, busy, error, notice, identityValid, login, logout, request: http.request.bind(http) };
});
