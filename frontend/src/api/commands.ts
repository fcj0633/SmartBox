import type { Command, CommandKind } from './types';
export interface FormValues {
  on: boolean; duration: number; output: string; mode: string; seed: number; profile: string;
  tempMin: number; tempMax: number; humiMin: number; humiMax: number; tempHyst: number; humiHyst: number;
}
export function buildCommand(kind: CommandKind, form: FormValues): Command {
  const integer = (n: number, min: number, max: number) => Number.isInteger(n) && n >= min && n <= max;
  let params: Record<string, unknown>;
  switch (kind) {
    case 'sentry':
      if (!integer(form.duration, 0, 86400)) throw new Error('布防时长须为0～86400秒的整数');
      params = { on: form.on, duration_s: form.duration, sentry_revision: 1 }; break;
    case 'output':
      if (!['light','fan','indicator'].includes(form.output) || !integer(form.duration, 1, 30)) throw new Error('输出类型或时长不正确（1～30秒）');
      params = { kind: form.output, on: form.on, duration_s: form.duration }; break;
    case 'gps_mode':
      if (!['FIXED','ROUTE','WALK'].includes(form.mode) || !integer(form.seed, 0, 4294967295)) throw new Error('定位模式或随机种子不正确');
      params = { mode: form.mode, seed: form.seed }; break;
    case 'profile':
      if (!form.profile.trim() || ![form.tempMin,form.tempMax,form.humiMin,form.humiMax,form.tempHyst,form.humiHyst].every(Number.isFinite) || form.tempMin >= form.tempMax || form.humiMin >= form.humiMax || form.tempHyst <= 0 || form.humiHyst <= 0) throw new Error('请检查画像标识、上下限与正回差');
      params = { profile_id: form.profile, temp_min_c: form.tempMin, temp_max_c: form.tempMax, humi_min_pct: form.humiMin, humi_max_pct: form.humiMax, temp_hysteresis_c: form.tempHyst, humi_hysteresis_pct: form.humiHyst }; break;
    case 'calibrate': params = {}; break;
  }
  return { cmd: kind, params };
}
export const defaultForm = (): FormValues => ({ on: true, duration: 10, output: 'light', mode: 'FIXED', seed: 1, profile: 'NORMAL', tempMin: 0, tempMax: 40, humiMin: 20, humiMax: 80, tempHyst: 2, humiHyst: 3 });
export function actualFields(actual: Record<string, unknown>): [string, unknown][] {
  const allowed: Record<string,string> = { sentry: '哨兵', sentry_revision: '哨兵修订', profile_id: '已采用画像', mode: '定位模式', calibrated: '模拟校准', kind: '输出类型', on: '输出开关' };
  return Object.entries(actual).filter(([k]) => k in allowed).map(([k,v]) => [allowed[k]!, v]);
}
