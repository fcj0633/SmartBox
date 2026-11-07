<script setup lang="ts">
import { watch } from 'vue';
import { useRouter } from 'vue-router';
import { useSession } from '../stores/session';
import { roles, time } from '../domain';
const session = useSession(), router = useRouter();
watch(() => session.identityValid, valid => { if (!valid) void router.replace('/login'); });
function logout() { session.logout(); void router.replace('/login'); }
</script>
<template>
  <RouterView v-if="$route.path === '/login'" />
  <div v-else class="workspace"><aside class="sidebar"><RouterLink to="/boxes" class="wordmark">SmartBox<span class="brand-square"></span></RouterLink><p class="sidebar-label">监测工作台</p><RouterLink to="/boxes" class="nav-item">▣ <span>我的物流箱</span><span class="nav-count">{{ session.user?.box_ids.length }}</span></RouterLink><div class="sidebar-bottom"><span class="badge mock">P1 · 模拟环境</span><p>监测可见，状态可溯。</p><small>真实板端接入待后续验证</small></div></aside><div class="main-column"><header class="topbar"><div class="sync-status" role="status"><span class="line-dot" :class="{ disconnected: session.connection !== 'connected' }"></span>{{ session.connection === 'connected' ? '实时连接已建立' : session.connection === 'connecting' ? '正在连接实时服务' : '实时连接中断' }}<span class="sync-time">最后同步 {{ time(session.lastSync) }}</span></div><div class="account"><span>{{ session.user?.username }}</span><span class="role-label">{{ session.user && roles[session.user.role] }}</span><button class="text-button" @click="logout">退出登录</button></div></header><main class="page-content"><RouterView /></main><footer class="workspace-footer">SMARTBOX <span>物流箱监测与控制 / 数据与动作均以当前验证范围为准</span></footer></div></div>
</template>
