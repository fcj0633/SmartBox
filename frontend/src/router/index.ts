import { createRouter, createWebHistory } from 'vue-router';
import { useSession } from '../stores/session';
import Login from '../features/Login.vue';
import Boxes from '../features/Boxes.vue';
import Detail from '../features/Detail.vue';
import Enterprise from '../business/Enterprise.vue';
import Rider from '../business/Rider.vue';
import TransportDetail from '../business/TransportDetail.vue';
import Track from '../business/Track.vue';
export const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/track', component: Track, meta: { public: true } },
  { path: '/enterprise', component: Enterprise, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/transports/:id', component: TransportDetail, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/rider', component: Rider, meta: { business: true, role: 'COURIER' } },
  { path: '/rider/transports/:id', component: TransportDetail, meta: { business: true, role: 'COURIER' } },
  { path: '/login', component: Login }, { path: '/boxes', component: Boxes },
  { path: '/boxes/:boxId', component: Detail }, { path: '/:pathMatch(.*)*', redirect: '/boxes' },
] });
router.beforeEach(to => {
  const session = useSession();
  if (to.meta.public) return;
  if (to.meta.role && session.identityValid && session.user?.role !== to.meta.role) return '/boxes';
  if (!session.identityValid && to.path !== '/login') return '/login';
  if (session.identityValid && to.path === '/login') return '/boxes';
});
