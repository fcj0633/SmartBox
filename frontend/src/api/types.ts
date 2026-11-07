export type Role = 'LOGISTICS_OPERATOR' | 'LOGISTICS_VIEWER' | 'DELIVERY_USER' | 'COURIER';
export type ExactInteger = number | bigint;
export interface User { username: string; role: Role; box_ids: string[] }
export interface Sample {
  value: Record<string, unknown> | null; valid: boolean; stale: boolean;
  freshness: 'FRESH' | 'STALE' | 'UNKNOWN'; observed_at: number | null;
  received_at: number | null; time_quality: string; src: string | null;
}
export interface Box {
  box_id: string; online_mask: number; connectivity: string;
  env: Sample; pose?: Sample; loc: Sample;
  alarm: { assessment: string; active: boolean | null; level: string | null };
  door: { state: string; confidence: string };
  sentry?: { state: string; revision: ExactInteger };
}
export interface Node { node_id: string; role: 'ENV' | 'POSE'; caps: number; required: boolean; online: boolean; boot_id: number | null; last_received_at: number | null }
export type CommandKind = 'sentry' | 'output' | 'gps_mode' | 'profile' | 'calibrate';
export interface Command { cmd: CommandKind; params: Record<string, unknown>; target_node_id?: string }
export interface Operation { operation_id: string; box_id: string; status: string; nodes: { node_id: string; request_id: string; status: string; code: string | null; actual_state: Record<string, unknown> }[] }
export interface Envelope<T> { data: T; request_id: string }
