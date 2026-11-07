import type { Box, Role, Sample } from './types';
import { canDiagnose } from '../domain';
function sample(source: Sample | undefined, keys: string[]): Sample | undefined {
  if (!source) return undefined;
  return { value: source.value ? Object.fromEntries(keys.filter(key => key in source.value!).map(key => [key, source.value![key]])) : null,
    valid: source.valid, stale: source.stale, freshness: source.freshness, observed_at: source.observed_at,
    received_at: source.received_at, time_quality: source.time_quality, src: source.src };
}
// Frontend field whitelist complements server authorization; it never grants access.
export function projectBox(source: Box, role: Role): Box {
  const result: Box = { box_id: source.box_id, online_mask: source.online_mask, connectivity: source.connectivity,
    env: sample(source.env,['temp_c','humi_pct','lux','valid','light_on','fan_on'])!,
    loc: sample(source.loc,['lat','lon','valid','src'])!,
    door: {state:source.door.state,confidence:source.door.confidence},
    alarm: {assessment:source.alarm.assessment,active:source.alarm.active,level:source.alarm.level} };
  if (canDiagnose(role)) {
    result.pose = sample(source.pose,['acc_g','tilt_deg','state','valid']);
    if (source.sentry) result.sentry = { state:source.sentry.state, revision:source.sentry.revision };
  }
  return result;
}
