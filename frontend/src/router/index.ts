import { createRouter, createWebHistory } from 'vue-router';
import { useSession } from '../stores/session';
import Login from '../features/Login.vue';
import Enterprise from '../business/Enterprise.vue';
import EnterpriseTransports from '../business/enterprise/TransportsPage.vue';
import EnterpriseTransportDetail from '../business/enterprise/TransportDetail.vue';
import EnterpriseAssets from '../business/enterprise/AssetsPage.vue';
import EnterpriseBoxOverview from '../business/enterprise/BoxOverview.vue';
import EnterpriseAlarms from '../business/enterprise/AlarmsPage.vue';
import EnterpriseMap from '../business/enterprise/MapPage.vue';

export const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/login', component: Login },
  { path: '/enterprise', component: Enterprise, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/assets', component: EnterpriseAssets, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/assets/:boxId', component: EnterpriseBoxOverview, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/transports', component: EnterpriseTransports, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/transports/:id', component: EnterpriseTransportDetail, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/alarms', component: EnterpriseAlarms, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/map', component: EnterpriseMap, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/:pathMatch(.*)*', redirect: '/enterprise' },
] });

router.beforeEach(to => {
  const session = useSession();
  if (to.meta.role && session.identityValid && session.user?.role !== to.meta.role) return '/enterprise';
  if (!session.identityValid && to.path !== '/login') return { path: '/login', query: { next: to.fullPath } };
  if (session.identityValid && to.path === '/login') return '/enterprise';
  return true;
});
