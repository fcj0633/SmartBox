export type DirectionId = 'command' | 'industrial' | 'route';
export type PageId = 'dashboard' | 'boxes' | 'detail' | 'alarms' | 'operations';

export const directions: Array<{
  id: DirectionId;
  name: string;
  eyebrow: string;
  description: string;
  audience: string;
  preview: string;
}> = [
  {
    id: 'command',
    name: '运营指挥台',
    eyebrow: 'OPS COMMAND',
    description: '浅色高密度工作台，以表格、待办和状态摘要支持快速分流。',
    audience: '物流运营 · 异常处置',
    preview: '一屏概览 · 表格工作流 · 侧栏详情',
  },
  {
    id: 'industrial',
    name: '工业资产工作区',
    eyebrow: 'ASSET WORKSPACE',
    description: '深色资产工作区，以资产树、节点关系和趋势监控服务设备运维。',
    audience: '设备运维 · 技术管理',
    preview: '资产树 · 多面板监控 · 规则状态',
  },
  {
    id: 'route',
    name: '物流地图控制台',
    eyebrow: 'FLEET CONTROL',
    description: '地图优先的调度视图，以位置、轨迹和联动资产列表组织任务。',
    audience: '运输调度 · 现场运营',
    preview: '地图主视图 · 路线任务 · 位置抽屉',
  },
];

export const pages: Array<{ id: PageId; label: string; short: string }> = [
  { id: 'dashboard', label: '运营总览', short: '总览' },
  { id: 'boxes', label: '物流箱资产', short: '资产' },
  { id: 'detail', label: '物流箱详情', short: '详情' },
  { id: 'alarms', label: '告警中心', short: '告警' },
  { id: 'operations', label: '位置与设备', short: '地图 / 设备' },
];

export const boxes = [
  { id: 'SBX-24018', name: '冷链样箱 · 华东一号', route: '上海 → 苏州', status: '运输中', node: 'ENV + POSE', online: true, temp: '4.8', humidity: '61', light: '18', updated: '14:32:08', health: '关注', lat: 31.2304, lng: 121.4737, profile: '生鲜冷链 V2' },
  { id: 'SBX-24021', name: '医药周转箱 · B组', route: '杭州 → 上海', status: '待签收', node: 'ENV + POSE', online: true, temp: '5.1', humidity: '58', light: '—', updated: '14:31:46', health: '正常', lat: 30.2741, lng: 120.1551, profile: '医药恒温 V1' },
  { id: 'SBX-24026', name: '精密仪器箱 · 07号', route: '南京 → 无锡', status: '运输中', node: 'ENV', online: false, temp: '—', humidity: '—', light: '—', updated: '12:58:13', health: '离线', lat: 32.0603, lng: 118.7969, profile: '精密器材 V1' },
  { id: 'SBX-24031', name: '生物样本箱 · C线', route: '上海 → 嘉兴', status: '已签收', node: 'ENV + POSE', online: true, temp: '5.0', humidity: '59', light: '2', updated: '14:29:21', health: '正常', lat: 30.7461, lng: 120.7555, profile: '生物样本 V2' },
];

export const alarmRows = [
  { id: 'ALT-00842', severity: '严重', title: '环境温度接近上限', box: 'SBX-24018', source: 'ENV / temperature', value: '4.8 °C · 阈值 5.0 °C', started: '14:26:18', duration: '06 分钟', state: '待确认', note: '连续 4 帧接近画像上限；演示告警，不代表真实规则引擎输出。' },
  { id: 'ALT-00839', severity: '警告', title: '设备心跳中断', box: 'SBX-24026', source: 'ENV / connectivity', value: '最后上报 12:58:13', started: '12:59:43', duration: '1 小时 32 分', state: '处理中', note: '节点在线判断以演示数据呈现；当前生产系统请以实际服务端状态为准。' },
  { id: 'ALT-00831', severity: '信息', title: '箱体进入围栏范围', box: 'SBX-24021', source: '位置 / geofence', value: '杭州东站 · Mock', started: '11:42:06', duration: '已恢复', state: '已确认', note: '围栏和坐标为规划功能演示。' },
];

export const events = [
  { time: '14:32:08', title: 'ENV 上报环境快照', box: 'SBX-24018', tone: 'teal', detail: '温度 4.8 °C · 湿度 61% · Demo' },
  { time: '14:29:21', title: '箱体完成交接', box: 'SBX-24031', tone: 'blue', detail: '物流事件 · Mock Data' },
  { time: '14:26:18', title: '环境阈值告警待确认', box: 'SBX-24018', tone: 'orange', detail: '演示告警 · 规则引擎 Planned' },
  { time: '12:59:43', title: 'ENV 心跳超过演示窗口', box: 'SBX-24026', tone: 'red', detail: '离线状态 · Mock Data' },
];

export const trendA = '0,62 34,56 68,58 102,42 136,46 170,32 204,37 238,25 272,28 306,16 340,20';
export const trendB = '0,44 34,40 68,43 102,36 136,39 170,24 204,28 238,23 272,31 306,20 340,24';

export function badgeClass(value: string) {
  if (['正常', '运输中', '已确认', '在線', '在线'].includes(value)) return 'good';
  if (['严重', '离线', '高'].includes(value)) return 'critical';
  if (['警告', '关注', '待确认', '待处理', '处理中'].includes(value)) return 'warning';
  return 'neutral';
}
