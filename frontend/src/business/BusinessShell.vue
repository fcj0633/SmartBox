<script setup lang="ts">
import { useSession } from '../stores/session';
import { useRouter } from 'vue-router';
import './business.css';
const session=useSession(),router=useRouter();
function logout(){session.logout();void router.replace('/login');}
</script>
<template>
 <div class="business-shell">
  <header class="business-header"><RouterLink class="business-brand" :to="session.user?.role==='COURIER'?'/rider':'/enterprise'"><span>S</span>SmartBox <small>运输工作台</small></RouterLink><nav><RouterLink v-if="session.user?.role==='COURIER'" to="/rider">我的运输</RouterLink><RouterLink v-else to="/enterprise">企业资产</RouterLink><RouterLink to="/boxes">设备监测</RouterLink><button class="biz-text" @click="logout">退出</button></nav></header>
  <main class="business-content"><slot /></main>
  <footer class="business-footer">SmartBox · GPS仍为模拟定位，监测与判断以实际数据质量为准。</footer>
 </div>
</template>
