<script setup lang="ts">
import { watch } from 'vue';
import { useRouter } from 'vue-router';
import { useSession } from '../stores/session';
import { roles, time } from '../domain';

const session = useSession();
const router = useRouter();
watch(() => session.identityValid, valid => { if (!valid && !router.currentRoute.value.meta.public) void router.replace('/login'); });
function logout() { session.logout(); void router.replace('/login'); }
</script>

<template>
  <RouterView :key="$route.path" v-if="$route.path === '/login' || $route.meta.public || $route.meta.business" />
  <div v-else class="app-shell">
    <aside class="app-sidebar">
      <RouterLink to="/boxes" class="brand-lockup" aria-label="SmartBox 首页">
        <span class="brand-mark">S</span>
        <span class="brand-copy"><strong>SMARTBOX</strong><small>MONITORING CLOUD</small></span>
      </RouterLink>

      <div class="sidebar-section-label">工作空间</div>
      <nav class="primary-nav" aria-label="主导航">
        <RouterLink v-if="session.businessHome !== '/boxes'" :to="session.businessHome" class="nav-link">运输工作台</RouterLink>
        <RouterLink to="/boxes" class="nav-link">
          <span class="nav-icon">▦</span><span>箱体总览</span><span class="nav-trailing">{{ session.user?.box_ids.length ?? 0 }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar-divider"></div>
      <div class="sidebar-context">
        <div class="context-icon">⌁</div>
        <div><strong>双节点监测</strong><small>ENV · POSE</small></div>
      </div>
      <div class="sidebar-bottom">
        <div class="service-indicator"><span class="service-dot"></span><span>应用服务</span><strong>已连接</strong></div>
        <p>SMARTBOX CONTROL CENTER</p>
        <small>数据状态以服务端实际回传为准</small>
      </div>
    </aside>

    <div class="app-main-column">
      <header class="app-topbar">
        <div class="topbar-left">
          <span class="topbar-kicker">SMARTBOX / OPERATIONS</span>
          <span class="topbar-separator">/</span>
          <span class="topbar-current">{{ String($route.path).startsWith('/boxes/') ? '箱体详情' : '箱体总览' }}</span>
        </div>
        <div class="topbar-right">
          <div class="connection-pill" :class="`connection-${session.connection}`" role="status">
            <span class="connection-dot"></span>
            {{ session.connection === 'connected' ? '实时通道已连接' : session.connection === 'connecting' ? '正在连接实时通道' : '实时通道未连接' }}
          </div>
          <div class="topbar-sync">最近同步 <strong>{{ time(session.lastSync) }}</strong></div>
          <div class="account-menu">
            <span class="avatar-mark">{{ session.user?.username?.slice(0, 1)?.toUpperCase() || 'U' }}</span>
            <span class="account-details"><strong>{{ session.user?.username }}</strong><small>{{ session.user && roles[session.user.role] }}</small></span>
            <button class="button-quiet" type="button" @click="logout">退出</button>
          </div>
        </div>
      </header>

      <main class="app-page-content">
        <RouterView />
      </main>
      <footer class="app-footer"><span>SMARTBOX · 设备监测与控制</span><span>状态展示遵循数据质量、授权范围与实际执行回执</span></footer>
    </div>
  </div>
</template>
