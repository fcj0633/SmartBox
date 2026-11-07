import type { Role, CommandKind, Sample } from './api/types';
export const roles: Record<Role,string> = { LOGISTICS_OPERATOR: '物流操作者', LOGISTICS_VIEWER: '物流寄收件人', DELIVERY_USER: '外卖用户', COURIER: '骑手' };
export const statusNames: Record<string,string> = { ONLINE: '在线', PARTIAL: '部分可用', OFFLINE: '离线', UNKNOWN: '未知', DISARMED: '已撤防', ARMING: '布防缓冲中', ARMED: '已布防', DEGRADED: '降级', PENDING: '已受理', RUNNING: '处理中', SUCCEEDED: '成功', FAILED: '失败', EXPIRED: '已超时', FRESH: '新鲜', STALE: '已过期', SNTP: '模拟已校时', UNSYNCED: '未校时', INSUFFICIENT: '证据不足' };
export const label = (value: string | null | undefined) => value ? statusNames[value] ?? value : '未知';
export const canDiagnose = (role?: Role) => !!role && role !== 'DELIVERY_USER';
export const canCommand = (role: Role | undefined, kind: CommandKind) => role === 'LOGISTICS_OPERATOR' || (role === 'COURIER' && kind === 'sentry');
export const terminal = (status: string) => ['SUCCEEDED','PARTIAL','FAILED','EXPIRED'].includes(status);
export const operationLabel = (status: string) => status === 'PARTIAL' ? '部分成功' : label(status);
export function time(value?: number | bigint | null): string {
  if (value == null) return '无时间记录';
  const n = typeof value === 'bigint' ? Number(value) : value;
  if (!Number.isSafeInteger(n) || Math.abs(n) > 8640000000000000) return '时间超出显示范围';
  return new Date(n).toLocaleString('zh-CN', { hour12: false });
}
export function quality(sample?: Sample, synchronized = true): string {
  if (!sample || !sample.value) return '无数据';
  if (!sample.valid) return '无效数据';
  if (!synchronized) return '同步中断 · 最后数据';
  return label(sample.freshness);
}
export const commandNames: Record<CommandKind,string> = { sentry: '布撤防', output: '模拟输出', gps_mode: '模拟定位模式', profile: '环境画像', calibrate: '模拟校准' };
