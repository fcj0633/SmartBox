<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useSession } from '../stores/session';

const session = useSession();
const router = useRouter();
const route = useRoute();
const username = ref(import.meta.env.VITE_DEFAULT_LOGIN_USERNAME || 'admin');
const password = ref(import.meta.env.VITE_DEFAULT_LOGIN_PASSWORD || 'admin');

async function login() {
  if (!username.value || !password.value || session.busy) return;
  await session.login(username.value, password.value);
  password.value = '';
  if (!session.identityValid) return;
  if (session.user?.role !== 'LOGISTICS_OPERATOR') {
    session.logout('企业控制台仅支持物流运营账号');
    return;
  }
  session.businessHome = '/enterprise';
  const next = typeof route.query.next === 'string' ? route.query.next : '';
  const target = next.startsWith('/') && !next.startsWith('//') ? router.resolve(next) : null;
  await router.replace(target?.meta.business ? next : '/enterprise');
}
</script>

<template>
  <main class="login-shell">
    <section class="login-brand-panel">
      <div class="brand-lockup login-brand"><span class="brand-mark">S</span><span class="brand-copy"><strong>SMARTBOX</strong><small>MONITORING CLOUD</small></span></div>
      <div class="login-brand-content">
        <div class="page-eyebrow"><span class="eyebrow-line"></span> TRANSPORT VISIBILITY</div>
        <h1>让每一只箱体的<br><em>运行状态</em>清晰可见。</h1>
        <p>环境观测、节点连接与设备操作，<br>在一个可信的工作台中统一查看。</p>
        <div class="login-feature-list">
          <div><span>01</span><strong>双节点实时监测</strong><small>ENV · POSE</small></div>
          <div><span>02</span><strong>远程操作与执行回执</strong><small>COMMAND · ACK</small></div>
          <div><span>03</span><strong>质量状态与权限隔离</strong><small>QUALITY · ACCESS</small></div>
        </div>
      </div>
      <div class="login-brand-footer"><span>SMARTBOX CONTROL CENTER</span><span>MONITORING / DIAGNOSTICS / CONTROL</span></div>
      <div class="login-orbit orbit-one"></div><div class="login-orbit orbit-two"></div>
    </section>

    <section class="login-access-panel">
      <form class="login-card" @submit.prevent="login">
        <div class="login-card-mark">S</div>
        <div class="page-eyebrow"><span class="eyebrow-line"></span> WORKSPACE ACCESS</div>
        <h2>欢迎回来</h2>
        <p class="login-description">使用已分配的项目账号登录监测工作台。</p>
        <label for="username">账号</label>
        <input id="username" v-model="username" autocomplete="username" required maxlength="128" :disabled="session.busy" placeholder="请输入账号">
        <label for="password">密码</label>
        <input id="password" v-model="password" type="password" autocomplete="current-password" required :disabled="session.busy" placeholder="请输入密码">
        <p v-if="session.error || session.notice" role="alert" class="inline-alert login-error"><strong>登录未完成</strong><span>{{ session.error || session.notice }}</span></p>
        <button class="button-primary login-submit" :disabled="session.busy">{{ session.busy ? '正在验证身份…' : '登录工作台　→' }}</button>
        <div class="login-security-note"><span>⌑</span> 会话仅保存在当前页面内存中，刷新后需要重新登录。</div>
      </form>
      <div class="login-access-footer">SmartBox · 设备监测与控制系统 <span>v0.1</span></div>
    </section>
  </main>
</template>
