import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { decode, encode } from '../../src/api/json';
import { Http, ApiError } from '../../src/api/http';
import { buildCommand, defaultForm, actualFields } from '../../src/api/commands';
import { canCommand, canDiagnose, quality, terminal, time } from '../../src/domain';
import { SerialRead } from '../../src/realtime/serial';
import { Realtime } from '../../src/realtime/connection';
import { mount } from '@vue/test-utils';
import SampleCard from '../../src/components/SampleCard.vue';
import { createPinia, setActivePinia } from 'pinia';
import { useSession } from '../../src/stores/session';
import { reactive } from 'vue';
import { projectBox } from '../../src/api/projection';

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const envelope = (data: unknown, status = 200) => new Response(encode({ data, request_id: 'unit-safe' }), { status });
describe('contract and presentation boundaries', () => {
  it('preserves uint64 and safe numbers through parse and serialize', () => {
    const x = decode<{ n: bigint; safe: number; f: number }>('{"n":18446744073709551615,"safe":4294967295,"f":1.5}');
    expect(x.n).toBe(18446744073709551615n); expect(x.safe).toBe(4294967295); expect(x.f).toBe(1.5);
    expect(encode(x)).toContain('18446744073709551615'); expect(decode(encode(x))).toEqual(x);
  });
  it('rejects malformed JSON and nonfinite numbers', () => { expect(() => decode('{')).toThrow(); expect(() => decode('{"n":NaN}')).toThrow(); expect(() => decode('{"n":1e999}')).toThrow(); });
  it('unrepresentable UTC cannot crash a sample card or become an invented date', () => { expect(time(18446744073709551615n)).toBe('时间超出显示范围');expect(time(null)).toBe('无时间记录');expect(time(0n)).not.toBe('无时间记录'); });
  it('allows only five operator actions and sentry for courier', () => {
    for (const kind of ['sentry','output','gps_mode','profile','calibrate'] as const) { expect(canCommand('LOGISTICS_OPERATOR', kind)).toBe(true); expect(canCommand('DELIVERY_USER', kind)).toBe(false); expect(canCommand('LOGISTICS_VIEWER', kind)).toBe(false); expect(canCommand('COURIER', kind)).toBe(kind === 'sentry'); }
    expect(canDiagnose('DELIVERY_USER')).toBe(false); expect(canCommand(undefined, 'sentry')).toBe(false);
  });
  it('sentry requires integer duration including zero, revision is adapter-only', () => {
    const f = defaultForm(); f.duration = 0; expect(buildCommand('sentry', f).params).toEqual({ on: true, duration_s: 0, sentry_revision: 1 });
    f.duration = 86401; expect(() => buildCommand('sentry', f)).toThrow(); f.duration = 1.5; expect(() => buildCommand('sentry', f)).toThrow();
  });
  it('output and seed bounds are checked before a request', () => {
    const f = defaultForm(); f.duration = 30; expect(buildCommand('output', f).params.duration_s).toBe(30);
    f.duration = 31; expect(() => buildCommand('output', f)).toThrow(); f.seed = 4294967295; expect(buildCommand('gps_mode', f).params.seed).toBe(4294967295);
    f.seed++; expect(() => buildCommand('gps_mode', f)).toThrow();
  });
  it('profile validates ordered finite thresholds and positive hysteresis', () => {
    const f = defaultForm(); expect(buildCommand('profile', f).params.profile_id).toBe('NORMAL'); f.tempMin = f.tempMax; expect(() => buildCommand('profile', f)).toThrow();
    f.tempMin = 0; f.humiHyst = 0; expect(() => buildCommand('profile', f)).toThrow(); f.humiHyst = 2; f.tempMax = Infinity; expect(() => buildCommand('profile', f)).toThrow();
    expect(buildCommand('calibrate', f).params).toEqual({});
  });
  it('unknown sample is never normal, and actual state has a whitelist', () => {
    expect(quality(undefined)).toBe('无数据');
    expect(actualFields({ password: 'secret', profile_id: 'ADOPTED' })).toEqual([['已采用画像','ADOPTED']]);
    expect(terminal('EXPIRED')).toBe(true); expect(terminal('PARTIAL')).toBe(true); expect(terminal('RUNNING')).toBe(false);
  });
  it('sample component preserves zero, missing values and escaped text', () => {
    const sample = { value: { temp_c: 0, lux: null, text: '<img src=x onerror=alert(1)>' }, valid: true, stale: true, freshness: 'UNKNOWN' as const, observed_at: null, received_at: null, time_quality: 'UNSYNCED', src: null };
    const view = mount(SampleCard, { props: { title: 'test', sample, synchronized: true, fields: [{ key:'temp_c',label:'温度',unit:'°C' },{ key:'lux',label:'光照',unit:'' },{ key:'text',label:'文本',unit:'' }] } });
    expect(view.text()).toContain('未知'); expect(view.text()).toContain('0'); expect(view.text()).toContain('—'); expect(view.find('img').exists()).toBe(false); expect(view.text()).toContain('<img');
    view.unmount();
  });
});
describe('HTTP, scheduling and session isolation', () => {
  it('only non-login 401 ends the session, uses bearer and no cookies', async () => {
    const http = new Http(); http.token = 'test-only'; const expired = vi.fn(); http.onUnauthorized = expired;
    const fetcher = vi.fn().mockResolvedValue(envelope(null,401)); vi.stubGlobal('fetch', fetcher);
    await expect(http.request('/me')).rejects.toBeInstanceOf(ApiError); expect(expired).toHaveBeenCalledOnce();
    expired.mockClear(); await expect(http.request('/auth/login',{ method:'POST',login:true })).rejects.toThrow(); expect(expired).not.toHaveBeenCalled();
    expect(fetcher.mock.calls[0]![1].credentials).toBe('omit'); expect(fetcher.mock.calls[0]![1].headers.Authorization).toBe('Bearer test-only');
  });
  it('an old request cannot expire a new token', async () => {
    const http = new Http(); http.token='old'; const expired=vi.fn(); http.onUnauthorized=expired;
    let finish!: (x:Response)=>void; vi.stubGlobal('fetch',vi.fn(()=>new Promise(resolve=>{ finish=resolve; })));
    const request=http.request('/me').catch(()=>{}); http.token='new'; finish(envelope(null,401)); await request; expect(expired).not.toHaveBeenCalled();
  });
  it('serial read coalesces notifications and aborts on disposal', async () => {
    vi.useFakeTimers(); let finish!:()=>void; let signal!:AbortSignal;
    const read=vi.fn((s:AbortSignal)=>{signal=s;return new Promise<void>(resolve=>{finish=resolve;});});
    const reader=new SerialRead(read,1000); reader.trigger(); await vi.advanceTimersByTimeAsync(1);
    reader.trigger(); reader.trigger(); expect(read).toHaveBeenCalledOnce(); finish(); await Promise.resolve();
    await vi.advanceTimersByTimeAsync(1000); expect(read).toHaveBeenCalledTimes(2); reader.stop(); expect(signal.aborted).toBe(true); finish(); await vi.advanceTimersByTimeAsync(2000); expect(read).toHaveBeenCalledTimes(2);
  });
  it('logout clears account data, pending request and old login completion', async () => {
    setActivePinia(createPinia()); const store=useSession();
    let finish!: (response:Response)=>void; vi.stubGlobal('fetch',vi.fn(()=>new Promise(resolve=>{finish=resolve;})));
    const login=store.login('unit','not-a-real-password'); store.logout(); finish(envelope({access_token:'unit',expires_at:Date.now()+10000})); await login;
    expect(store.user).toBeNull(); expect(store.boxes).toEqual([]); expect(store.pending).toBeNull(); expect(store.identityValid).toBe(false);
  });
  it('reactive confirmation preserves request identity across a lost response', async () => {
    setActivePinia(createPinia());const store=useSession();
    store.user={username:'unit',role:'LOGISTICS_OPERATOR',box_ids:['BOX-0001']} as any;
    store.box={box_id:'BOX-0001'} as any;
    const fetcher=vi.fn().mockRejectedValueOnce(new TypeError('network')).mockResolvedValueOnce(envelope({operation_id:'unit-operation'}));vi.stubGlobal('fetch',fetcher);
    await store.submit(reactive(buildCommand('sentry',defaultForm())));
    expect(store.pending).not.toBeNull();const saved=store.pending!.key;await store.submit();
    expect(fetcher.mock.calls[0]![1].headers['Idempotency-Key']).toBe(saved);expect(fetcher.mock.calls[1]![1].headers['Idempotency-Key']).toBe(saved);
    expect(store.operation?.operation_id).toBe('unit-operation');expect(store.pending).toBeNull();store.logout();
  });
  it('frontend projection removes nested unexpected and role-restricted fields', () => {
    const raw={box_id:'BOX-0001',env:{value:{temp_c:0,password:'forbidden'}},pose:{value:{tilt_deg:0}},loc:{value:{lat:1,lon:2,observation:{uptime_ms:1n}}},sentry:{state:'ARMED',revision:1n},door:{state:'UNKNOWN',confidence:'INSUFFICIENT',raw:'forbidden'},alarm:{assessment:'UNKNOWN',active:null,level:null},evidence:['forbidden']} as any;
    const view=projectBox(raw,'DELIVERY_USER');expect(view).not.toHaveProperty('pose');expect(view).not.toHaveProperty('sentry');expect(view).not.toHaveProperty('evidence');expect(view.env.value).not.toHaveProperty('password');expect(view.loc.value).not.toHaveProperty('observation');expect(view.door).not.toHaveProperty('raw');
  });
  it('Realtime handles subscription, messages and stops retries', async () => {
    vi.useFakeTimers(); const sockets: any[]=[];
    class Socket { static OPEN=1;readyState=1;onopen?:()=>void;onclose?:()=>void;onmessage?:(e:any)=>void;send=vi.fn();close=vi.fn(()=>this.onclose?.());constructor(){sockets.push(this);} }
    vi.stubGlobal('WebSocket',Socket); const changed=vi.fn(),resync=vi.fn(async()=>true),state=vi.fn();
    const rt=new Realtime(async()=> 'ticket',['BOX-0001'],state,changed,resync); await rt.connect(); sockets[0].onopen();
    expect(sockets[0].send.mock.calls[0][0]).toContain('subscribe'); sockets[0].onmessage({data:'{"type":"snapshot","box_id":"BOX-0001"}'}); expect(changed).toHaveBeenCalledTimes(2);
    sockets[0].onclose(); await Promise.resolve(); rt.stop(); await vi.advanceTimersByTimeAsync(40000); expect(sockets.length).toBe(1);
  });
});
