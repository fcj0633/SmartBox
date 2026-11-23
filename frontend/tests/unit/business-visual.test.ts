import {expect,it} from 'vitest';
import {mount} from '@vue/test-utils';
import SampleHistory from '../../src/business/components/SampleHistory.vue';
import EnvironmentSummary from '../../src/business/components/EnvironmentSummary.vue';

it('orders actual samples chronologically and does not bridge an invalid measurement',()=>{
 const wrapper=mount(SampleHistory,{props:{samples:[
  {id:3,observed_at:3000,temperature:5,humidity:50,valid:true},
  {id:1,observed_at:1000,temperature:0,humidity:40,valid:true},
  {id:2,observed_at:2000,temperature:null,humidity:null,valid:false},
 ]}});
 expect(wrapper.findAll('circle')).toHaveLength(2);
 expect(wrapper.findAll('.data-line')).toHaveLength(2);
 expect(wrapper.findAll('tbody tr')[0].text()).toContain('0 °C');
 expect(wrapper.findAll('tbody tr')[1].text()).toContain('无效');
 wrapper.unmount();
});
it('does not expose invalid readings as current usable measurements',()=>{
 const wrapper=mount(EnvironmentSummary,{props:{environment:{assessment:'UNKNOWN',latest_temperature:0,latest_humidity:50,observed_at:1000,time_quality:'SNTP',data_state:'INVALID'}}});
 expect(wrapper.find('h2').text()).toContain('采样无效');
 expect(wrapper.findAll('.reading-number').every(e=>e.text().includes('—'))).toBe(true);
 wrapper.unmount();
});
