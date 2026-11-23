export interface Point { x: number; y: number; place: string }
export interface Route { code: string; name: string; from: string; to: string; duration_ms: number; points: Point[] }
export interface Position { x: number; y: number; place: string; route_code: string; route_name: string; progress: number; position_at: number; stale: boolean; finished: boolean; source: string }
export interface Environment { assessment: string; level: string | null; temperature: number | null; humidity: number | null; lux: number | null; observed_at: number | null; time_quality: string; data_state: string; received_at: number | null }
export interface Pose { state: string | null; tilt_deg: number | null; acc_g: number[]; valid: boolean; observed_at: number | null; time_quality: string; data_state: string }
export interface AlarmState { assessment: string; active: boolean | null; level: string | null; active_count: number }
export interface Node { node_id: string; role: string; caps: number; required: boolean; online: boolean; last_seen_at: number | null; last_received_at: number | null }
export interface TaskSummary { task_id: number; transport_no: string; status: string; item_name: string | null; box_id: string; box_display_name: string; started_at: number | null; ended_at: number | null }
export interface Box { box_id: string; display_name: string; asset_source: string; purpose?: string | null; notes?: string | null; demo_scenario?: string | null; demo_enabled?: boolean; connectivity: string; data_state: string; environment: Environment; pose?: Pose | null; alarm: AlarmState; current_transport: TaskSummary | null; last_completed_transport: TaskSummary | null; position: Position | null; last_seen_at: number | null; nodes: Node[] }
export interface SamplePoint { id: number; observed_at: number; received_at: number | null; valid: boolean; temperature: number | null; humidity: number | null; lux: number | null; state: string | null; tilt_deg: number | null; acc_g: number[]; time_quality: string }
export interface CommandNode { node_id: string; status: string; result: string | null; code: string | null; ack_received_at: number | null }
export interface CommandOperationNode { node_id: string; request_id: string; status: string; code: string | null; actual_state: Record<string, unknown> }
export interface CommandOperation { operation_id: string; box_id: string; status: string; nodes: CommandOperationNode[] }
export interface CommandRow { operation_id: string; cmd: string; status: string; created_at: number; updated_at: number; requested_by: string | null; params: string; nodes: CommandNode[] }
export interface LogRow { at: number; source: string; title: string; detail: string; level: string }
export interface StartCheck { online: boolean; risk: boolean; blockers: string[] }
export interface Task { task_id: number; transport_no: string; box_id: string; box_display_name: string; status: string; item_name: string | null; item_description: string | null; operator_username: string | null; created_at: number; started_at: number | null; ended_at: number | null; route: Route | null; position: Position | null; start_check: StartCheck }
export interface Kpis { box_total: number; online: number; partial: number; offline: number; unknown: number; active_transports: number; active_alarm_boxes: number; unknown_alarm_boxes: number; completed_today: number }
export interface Activity { at: number; type: string; box_id: string; box_display_name: string; task_id: number | null; title: string; source: string }
export interface Overview { enterprise_id: number; enterprise_name: string; refreshed_at: number; kpis: Kpis; boxes: Box[]; tasks: Task[]; recent_activity: Activity[] }
export interface Sample { id: number; observed_at: number; temperature: number | null; humidity: number | null; valid: boolean }
export interface Event { id: number; observed_at: number; type: string; level: string; description: string }
export interface TaskDetail { task: Task; box: Box; route: Route | null; position: Position | null; environment: Environment; samples: Sample[]; events: Event[] }
export interface AlarmRow { box_id: string; box_display_name: string; alarm_key: string; active: boolean; level: string; started_at: number; updated_at: number; recovered_at: number | null; source_event_type: string | null; source_event_at: number | null; details: string }
export interface AlarmList { assessment_unknown_boxes: number; rows: AlarmRow[] }

export const dateTime = (value: number | null | undefined) => value == null ? '—' : new Date(value).toLocaleString('zh-CN', { hour12: false });
export const shortTime = (value: number | null | undefined) => value == null ? '—' : new Date(value).toLocaleTimeString('zh-CN', { hour12: false });
export const assetSourceLabel = (value: string) => value === 'SIMULATED' ? '模拟箱' : value === 'REAL' ? '实物箱' : '来源未知';
export const connectivityLabel = (value: string) => ({ ONLINE: '在线', PARTIAL: '部分离线', OFFLINE: '离线', UNKNOWN: '未知' }[value] ?? value);
export const taskStatusLabel = (value: string) => ({ READY: '待开始', ACTIVE: '运输中', COMPLETED: '已结束' }[value] ?? value);
export const taskStatusTone = (value: string) => value === 'ACTIVE' ? 'active' : value === 'READY' ? 'ready' : 'done';
export const scenarioLabel = (value?: string | null) => ({ normal: '正常演示', env_high: '高温', offline: '离线', stale: '数据过期', impact: '姿态冲击' }[value ?? ''] ?? '未设置');
export const scenarioTone = (value?: string | null) => value === 'env_high' ? 'warn' : value === 'impact' ? 'critical' : value === 'offline' ? 'critical' : value === 'stale' ? 'warn' : 'neutral';
export const demoEnabledLabel = (value?: boolean) => value === false ? '已停用' : '模拟运行中';
export const dataStateLabel = (value: string) => ({ FRESH: '新鲜', STALE: '已过期', INVALID: '无效', UNKNOWN: '未知' }[value] ?? value);
export const alarmAssessmentLabel = (value: string) => value === 'EVALUATED' ? '已判定' : '判定未知';
export const alarmLevelLabel = (value: string | null) => ({ CRITICAL: '严重', WARNING: '警告', INFO: '提示' }[value ?? ''] ?? '未知');
export const environmentTitle = (env: Environment) => env.assessment === 'EVALUATED' ? (env.level ? `${alarmLevelLabel(env.level)}告警` : '当前无活动告警') : '告警判定未知';
export const environmentValue = (env: Environment) => env.temperature == null ? '—' : `${env.temperature.toFixed(1)} °C`;
export const humidityValue = (env: Environment) => env.humidity == null ? '—' : `${env.humidity.toFixed(1)} %`;
export const qualityText = (env: Environment) => `${dataStateLabel(env.data_state)} · ${env.time_quality}`;
export const positionLabel = (position: Position | null) => position == null ? '未定位' : position.place;
export const isOnline = (box: Box) => box.connectivity === 'ONLINE';
export const activityLabel = (type: string) => ({ TASK_CREATED: '任务创建', TASK_STARTED: '开始运输', TASK_COMPLETED: '结束运输', ALARM_STARTED: '告警触发', ALARM_RECOVERED: '告警恢复', DEVICE_EVENT: '设备事件' }[type] ?? type);
export const riskText = (task: Task) => task.start_check.risk ? '存在活动告警或告警判定未知，开始时需要显式确认。' : '设备与告警检查通过。';
export const durationText = (route: Route | null) => route == null ? '—' : `${Math.round(route.duration_ms / 60000)} 分钟演示路线`;
export const poseStateLabel = (value: string | null | undefined) => ({ NORMAL: '正常', TILTED: '倾斜', SIDEWAYS: '侧翻', INVERTED: '倒置', UNKNOWN: '未知' }[value ?? ''] ?? '未知');
export const logSourceLabel = (value: string) => ({ device: '设备', task: '运输', audit: '审计', command: '命令' }[value] ?? value);
export const commandStatusLabel = (value: string) => ({ PENDING: '待发送', RUNNING: '执行中', SUCCEEDED: '成功', PARTIAL: '部分成功', FAILED: '失败', EXPIRED: '超时' }[value] ?? value);
export const commandKindLabel = (value: string) => ({ output: '输出控制', sentry: '哨兵', profile: '环境画像', gps_mode: '定位模式', calibrate: '姿态校准' }[value] ?? value);
export const alarmMessage = (details: string) => {
  try {
    const value = JSON.parse(details) as { message?: string };
    return value.message || details;
  } catch {
    return details;
  }
};
