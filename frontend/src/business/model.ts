import { onUnmounted, ref } from 'vue';
import { SerialRead } from '../realtime/serial';
import { ApiError } from '../api/http';
export interface Task { task_id: number; transport_no: string; box_id?: string; status: string; item_name: string|null; item_description: string|null; started_at: number|null; ended_at: number|null }
export interface Rider { user_id: number; username: string }
export interface Asset { box_id: string; display_name: string; source?: string; rider_user_id?: number|null; rider_username?: string|null }
export interface Monitoring { task: Task; environment: { assessment: string; latest_temperature: number|null; latest_humidity: number|null; observed_at: number|null; time_quality: string; data_state: string }; samples: {id:number;observed_at:number;temperature:number|null;humidity:number|null;valid:boolean}[]; events: {id:number;observed_at:number;type:string;level:string;description:string}[] }
export const stateName = (value: string) => ({ READY:'待开始', ACTIVE:'运输中', COMPLETED:'已结束' }[value] ?? value);
export const date = (value: number|null) => value===null?'—':new Date(value).toLocaleString('zh-CN',{hour12:false});
export const errorText = (error: unknown) => error instanceof ApiError ? ({BOX_OFFLINE:'箱体节点离线，请等待设备上线后重试',RISK_CONFIRMATION_REQUIRED:'环境状态有风险或尚未完成判断，需要确认后开始',BOX_ALREADY_ACTIVE:'箱体已有进行中的运输',ACTIVE_TRANSPORT_EXISTS:'运输中不能收回箱体',INVALID_TRANSPORT_ACCESS:'编号或查看凭据无效或已过期，请重新验证',ACCESS_RATE_LIMITED:'尝试次数过多，请15分钟后再试'}[error.code] ?? error.message) : '连接暂不可用，请重试';
export function usePolling(read: (signal: AbortSignal)=>Promise<void>, clear: ()=>void = ()=>{}) {
 const error=ref(''),loading=ref(true);let disposed=false;
 const reader=new SerialRead(async signal=>{try{await read(signal);if(!disposed)error.value='';}catch(e){if(!signal.aborted&&!disposed){if(e instanceof ApiError&&[403,404].includes(e.status))clear();error.value=errorText(e);}}finally{if(!disposed)loading.value=false;}},200);
 const timer=setInterval(()=>reader.trigger(),10000);reader.trigger();
 onUnmounted(()=>{disposed=true;clearInterval(timer);reader.stop();});
 return {error,loading,refresh:()=>reader.trigger()};
}
