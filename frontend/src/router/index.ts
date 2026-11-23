import { createRouter, createWebHistory } from 'vue-router';
import { useSession } from '../stores/session';
import Login from '../features/Login.vue';
import Boxes from '../features/Boxes.vue';
import Detail from '../features/Detail.vue';
import Enterprise from '../business/Enterprise.vue';
import EnterpriseTransports from '../business/enterprise/TransportsPage.vue';
import EnterpriseTransportDetail from '../business/enterprise/TransportDetail.vue';
import EnterpriseAssets from '../business/enterprise/AssetsPage.vue';
import EnterpriseBoxOverview from '../business/enterprise/BoxOverview.vue';
import EnterpriseAlarms from '../business/enterprise/AlarmsPage.vue';
import EnterpriseMap from '../business/enterprise/MapPage.vue';
import Rider from '../business/Rider.vue';
import TransportDetail from '../business/TransportDetail.vue';
import Track from '../business/Track.vue';
import RiderPrepare from '../business/rider/RiderPrepare.vue';

export const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/track', component: Track, meta: { public: true } },
  {
    path: '/enterprise', component: Enterprise, meta: { business: true, role: 'LOGISTICS_OPERATOR' },
    beforeEnter: to => to.query.view === 'assets' ? { path: '/enterprise/assets' } : to.query.view === 'transports' ? { path: '/enterprise/transports' } : true,
  },
  { path: '/enterprise/assets', component: EnterpriseAssets, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/assets/:boxId', component: EnterpriseBoxOverview, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/transports', component: EnterpriseTransports, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/transports/:id', component: EnterpriseTransportDetail, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/alarms', component: EnterpriseAlarms, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/enterprise/map', component: EnterpriseMap, meta: { business: true, role: 'LOGISTICS_OPERATOR' } },
  { path: '/rider', component: Rider, meta: { business: true, role: 'COURIER' } },
  ...['boxes','history','account'].map(page=>({path:'/rider/'+page,component:Rider,meta:{business:true,role:'COURIER'}})),
  { path: '/rider/prepare', component: RiderPrepare, meta: { business: true, role: 'COURIER' } },
  { path: '/rider/transports/:id', component: TransportDetail, meta: { business: true, role: 'COURIER' } },
  { path: '/login', component: Login }, { path: '/boxes', component: Boxes },
  { path: '/boxes/:boxId', component: Detail }, { path: '/:pathMatch(.*)*', redirect: '/boxes' },
] });
router.beforeEach(to => {
  const session = useSession();
  if (to.meta.public) return;
  if (to.meta.role && session.identityValid && session.user?.role !== to.meta.role) return '/boxes';
  if (!session.identityValid && to.path !== '/login') return to.meta.business ? {path:'/login',query:{next:to.fullPath}} : '/login';
  if (session.identityValid && to.path === '/login') return '/boxes';
});
