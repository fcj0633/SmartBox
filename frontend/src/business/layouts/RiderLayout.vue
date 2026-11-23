<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { useSession } from '../../stores/session';
import Icon from '../components/Icon.vue';
import '../styles/transport.css';
const route = useRoute(), router = useRouter(), session = useSession();
const nav = [{ path: '/rider', label: '工作台', icon: 'task' }, { path: '/rider/boxes', label: '我的箱体', icon: 'box' }, { path: '/rider/history', label: '运输记录', icon: 'archive' }, { path: '/rider/account', label: '我的', icon: 'user' }];
function logout() { session.logout(); void router.replace('/login'); }
</script>
<template>
  <div class="sb-transport sb-rider">
    <header class="product-header">
      <RouterLink to="/rider" class="brand"><span class="brand-symbol">
          <Icon name="box" :size="24" />
        </span><strong>SmartBox</strong><span class="brand-context">个人运输</span></RouterLink>
      <nav class="desktop-nav" aria-label="主要导航">
        <RouterLink v-for="n in nav" :key="n.path" :to="n.path" :class="{current:route.path===n.path}">{{n.label}}
        </RouterLink>
      </nav><button class="text-button" @click="logout">退出</button>
    </header>
    <main class="main-content">
      <slot />
    </main>
    <footer class="product-footer">SmartBox · 环境结论与访问范围以服务端结果为准。</footer>
    <nav class="mobile-nav" aria-label="移动导航">
      <RouterLink v-for="n in nav" :key="n.path" :to="n.path" :class="{current:route.path===n.path}">
        <Icon :name="n.icon" :size="21" /><span>{{n.label}}</span>
      </RouterLink>
    </nav>
  </div>
</template>