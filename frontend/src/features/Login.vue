<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useSession } from '../stores/session';
const session = useSession(), router = useRouter();
const username = ref(''), password = ref('');
async function login() {
  if (!username.value || !password.value || session.busy) return;
  await session.login(username.value, password.value); password.value = '';
  if (session.identityValid) await router.replace('/boxes');
}
</script>
<template>
  <main class="login-page"><section class="login-story"><div class="wordmark">SmartBox<span class="brand-square"></span></div><p class="eyebrow">TRANSPORT VISIBILITY</p><h1>让运输中的每一刻，<br>都有迹可循。</h1><p>集中查看物流箱环境、位置与节点状态，<br>用清晰的数据，了解每一次变化。</p><div class="login-note"><span class="line-dot"></span>双节点监测 · 按角色授权 · 实时同步</div></section>
  <section class="login-form-wrap"><form class="login-form" @submit.prevent="login"><p class="eyebrow">WORKSPACE ACCESS</p><h2>登录监测工作台</h2><p class="subtle">使用已分配的项目账号继续。</p><label for="username">账号</label><input id="username" v-model="username" autocomplete="username" required maxlength="128" :disabled="session.busy" placeholder="请输入账号"><label for="password">密码</label><input id="password" v-model="password" type="password" autocomplete="off" required :disabled="session.busy" placeholder="请输入密码"><p v-if="session.error || session.notice" role="alert" class="error-box">{{ session.error || session.notice }}</p><button class="primary wide" :disabled="session.busy">{{ session.busy ? '正在登录…' : '进入工作台 →' }}</button><p class="login-foot">会话仅在当前页面保留，刷新后需重新登录。</p></form><span class="login-edition">SmartBox · 监测工作台</span></section></main>
</template>
