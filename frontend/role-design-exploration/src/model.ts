export type Role = 'rider' | 'customer';
export type Layout = 'a' | 'b';
export type Page = 'entry' | 'home' | 'box' | 'prepare' | 'task' | 'history' | 'environment' | 'alerts' | 'account' | 'expired';
export type Scenario = 'normal' | 'alarm' | 'offline' | 'invalid' | 'stale' | 'unknown' | 'empty' | 'revoked' | 'verify-error' | 'expired';
export const demoDate = '2026-11-23';
export const currentTime = '14:32';
export const demoCode = 'DEMO-4821';

export interface Box {
  id: string; name: string; source: 'personal' | 'assigned'; temp: number; humidity: number;
  nodes: string[]; profile: string;
}
export interface Task {
  id: string; boxId: string; item: string; description: string; state: 'ready' | 'active' | 'completed';
  start: string | null; end: string | null; temperature: number; humidity: number;
}
export const boxes: Box[] = [
  { id: 'SBX-24018', name: '随行箱 · 01', source: 'personal', temp: 4.8, humidity: 61, nodes: ['ENV', 'POSE'], profile: '生鲜保鲜 · 演示画像' },
  { id: 'SBX-24021', name: '周转箱 · 02', source: 'assigned', temp: 6.2, humidity: 58, nodes: ['ENV', 'POSE'], profile: '生鲜保鲜 · 演示画像' },
];
export function initialTasks(): Task[] {
  return [
    { id: 'DEMO-1123-018', boxId: 'SBX-24018', item: '鲜果礼盒', description: '保鲜运输 · 示例物品', state: 'active', start: '13:10', end: null, temperature: 4.8, humidity: 61 },
    { id: 'DEMO-1123-021', boxId: 'SBX-24021', item: '', description: '待绑定 · 示例任务', state: 'ready', start: null, end: null, temperature: 6.2, humidity: 58 },
    { id: 'DEMO-1123-006', boxId: 'SBX-24018', item: '蔬果组合', description: '已结束 · 示例物品', state: 'completed', start: '09:12', end: '10:40', temperature: 5.1, humidity: 60 },
  ];
}
export const scenarioOptions: Array<{id: Scenario; label: string}> = [
  {id:'normal',label:'正常样本'}, {id:'alarm',label:'环境异常'}, {id:'offline',label:'节点离线'},
  {id:'invalid',label:'采样无效'}, {id:'stale',label:'数据过期'}, {id:'unknown',label:'判断未知'},
  {id:'empty',label:'无可用箱体'}, {id:'revoked',label:'分配已失效'},
  {id:'verify-error',label:'验证失败'}, {id:'expired',label:'访问失效'},
];
export const pages: Array<{id:Page; label:string; roles:Role[]}> = [
  {id:'entry',label:'入口',roles:['rider','customer']}, {id:'home',label:'首页',roles:['rider','customer']},
  {id:'box',label:'箱体详情',roles:['rider']}, {id:'prepare',label:'选箱与绑定',roles:['rider']},
  {id:'task',label:'当前运输',roles:['rider','customer']}, {id:'history',label:'运输记录',roles:['rider']},
  {id:'environment',label:'环境历史',roles:['rider','customer']}, {id:'alerts',label:'异常记录',roles:['rider','customer']},
  {id:'account',label:'我的权限',roles:['rider']}, {id:'expired',label:'授权失效',roles:['rider','customer']},
];
export const stateNames = {ready:'待准备',active:'运输中',completed:'已结束'};
