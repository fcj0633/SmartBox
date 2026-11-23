<script setup lang="ts">
import { useSession } from '../../stores/session';
import { useRoute, useRouter } from 'vue-router';
import Icon from '../components/Icon.vue';
import '../styles/transport.css';
import '../styles/enterprise.css';
const session = useSession(), router = useRouter(), route = useRoute();
function logout() { session.logout(); void router.replace('/login'); }
</script>
<template>
  <div class="sb-transport sb-enterprise">
    <aside class="enterprise-sidebar">
      <RouterLink to="/enterprise" class="brand"><span class="brand-symbol">
          <Icon name="box" />
        </span><strong>SMARTBOX</strong></RouterLink>
      <div class="enterprise-workspace"><small>工作空间</small><strong>企业运输中心</strong></div>
      <p class="nav-label">运营工作台</p>
      <nav aria-label="企业导航">
        <RouterLink to="/enterprise" :class="{selected:!route.query.view}">
          <Icon name="task" />运营总览
        </RouterLink>
        <RouterLink to="/enterprise?view=assets" :class="{selected:route.query.view==='assets'}">
          <Icon name="box" />物流箱资产
        </RouterLink>
        <RouterLink to="/enterprise?view=transports" :class="{selected:route.query.view==='transports'}">
          <Icon name="archive" />运输任务
        </RouterLink>
        <RouterLink to="/boxes">
          <Icon name="chart" />设备监测
        </RouterLink>
      </nav>
      <div class="sidebar-account"><strong>{{session.user?.username}}</strong><small>企业工作空间</small><button
          @click="logout">退出登录</button></div>
    </aside>
    <div class="enterprise-workarea">
      <header class="enterprise-topbar"><span>运营中心 /
          <strong>运输工作区</strong></span><span>{{session.user?.username}}</span></header>
      <main class="main-content">
        <slot />
      </main>
      <footer class="product-footer">SmartBox · 任务与资产来自服务端，地图仅为明确标注的示意。</footer>
    </div>
  </div>
</template>