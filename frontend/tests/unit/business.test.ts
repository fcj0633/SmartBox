import { afterEach,expect,it,vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia,setActivePinia } from 'pinia';
import { Http,ApiError } from '../../src/api/http';
import MonitoringPanel from '../../src/business/MonitoringPanel.vue';
import { errorText } from '../../src/business/model';
import { router } from '../../src/router';
afterEach(()=>{vi.unstubAllGlobals();vi.restoreAllMocks();});
it('preserves a transport business rejection code',async()=>{
 vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({request_id:'safe',error:{code:'BOX_OFFLINE'}}),{status:409})));
 const http=new Http();try{await http.request('/rider/transports/1/start');throw Error('accepted');}catch(e){expect(e).toBeInstanceOf(ApiError);expect((e as ApiError).code).toBe('BOX_OFFLINE');expect(errorText(e)).toContain('离线');}
});
it('unknown assessment does not become normal, including a zero measurement',()=>{
 const view=mount(MonitoringPanel,{props:{data:{task:{task_id:1,transport_no:'TR-example',status:'COMPLETED',item_name:'书籍',item_description:null,started_at:1,ended_at:5},environment:{assessment:'UNKNOWN',latest_temperature:0,latest_humidity:50,observed_at:2,time_quality:'SNTP',data_state:'AVAILABLE'},samples:[],events:[]}}});
 expect(view.text()).toContain('尚未完成任务级判断');expect(view.text()).not.toContain('环境正常');expect(view.text()).toContain('0');view.unmount();
});
it('allows anonymous tracking while retaining the rider login guard',async()=>{
 setActivePinia(createPinia());await router.push('/track');expect(router.currentRoute.value.path).toBe('/track');await router.push('/rider');expect(router.currentRoute.value.path).toBe('/login');
});
