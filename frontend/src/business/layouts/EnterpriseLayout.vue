<script setup lang="ts">
import { computed } from 'vue';
import { useSession } from '../../stores/session';
import { useRoute, useRouter } from 'vue-router';
import Icon from '../components/Icon.vue';
import '../styles/transport.css';
import '../styles/enterprise.css';
const session = useSession(), router = useRouter(), route = useRoute();
const nav = [
  { path: '/enterprise', label: '运营总览', icon: 'task' },
  { path: '/enterprise/assets', label: '物流箱资产', icon: 'box' },
  { path: '/enterprise/transports', label: '运输任务', icon: 'archive' },
  { path: '/enterprise/alarms', label: '告警中心', icon: 'bell' },
  { path: '/enterprise/map', label: '位置与设备', icon: 'signal' },
];
const active = computed(() => nav.find(item => route.path === item.path || (item.path !== '/enterprise' && route.path.startsWith(item.path)))?.path ?? '/enterprise');
const title = computed(() => nav.find(item => item.path === active.value)?.label ?? '运营总览');
function logout() { session.logout(); void router.replace('/login'); }
</script>
<template>
  <div class="sb-transport sb-enterprise">
    <aside class="enterprise-sidebar">
      <RouterLink to="/enterprise" class="brand"><span class="brand-symbol"><Icon name="box" /></span><strong>SMARTBOX</strong></RouterLink>
      <div class="enterprise-workspace"><small>工作空间</small><strong>企业运输中心</strong></div>
      <p class="nav-label">运营工作台</p>
      <nav aria-label="企业导航">
        <RouterLink v-for="item in nav" :key="item.path" :to="item.path" :class="{ selected: active === item.path }">
          <Icon :name="item.icon" />{{ item.label }}
        </RouterLink>
      </nav>
      <div class="sidebar-account"><strong>{{ session.user?.username }}</strong><small>企业工作空间</small><button @click="logout">退出登录</button></div>
    </aside>
    <div class="enterprise-workarea">
      <header class="enterprise-topbar">
        <span>运营中心 / <strong>{{ title }}</strong><span v-if="active === '/enterprise/assets' && route.params.boxId"> / {{ route.params.boxId }}</span><span v-if="active === '/enterprise/transports' && route.params.id"> / {{ route.params.id }}</span></span>
        <span class="topbar-actions"><span class="topbar-mock">地图与位置为 MOCK</span><span>{{ session.user?.username }}</span></span>
      </header>
      <main class="main-content"><slot /></main>
      <footer class="product-footer">SmartBox · 资产与任务来自服务端；地图位置为明确标注的演示数据。</footer>
    </div>
  </div>
</template>
