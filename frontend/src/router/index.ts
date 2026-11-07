import { createRouter, createWebHistory } from 'vue-router';
import { useSession } from '../stores/session';
import Login from '../features/Login.vue';
import Boxes from '../features/Boxes.vue';
import Detail from '../features/Detail.vue';
export const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/login', component: Login }, { path: '/boxes', component: Boxes },
  { path: '/boxes/:boxId', component: Detail }, { path: '/:pathMatch(.*)*', redirect: '/boxes' },
] });
router.beforeEach(to => {
  const session = useSession();
  if (!session.identityValid && to.path !== '/login') return '/login';
  if (session.identityValid && to.path === '/login') return '/boxes';
});
