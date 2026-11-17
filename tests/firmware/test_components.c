/* Deterministic CMSIS substitute: only linked into host tests, never GN. */
#include "sb_config.h"
#include "sb_ports.h"
#include "sb_rules.h"
#include "cmsis_os2.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
static uint32_t tick=0,hz=1000;
static int locked,unlock_count,fail_queue,fail_thread,thread_count,deleted,timeout_exit;
static int running,delays,gate_probe,probing;
static jmp_buf gate_escape;
static osThreadFunc_t functions[3];
static bool ran[3];
static struct { SbSensorFrame frames[8];unsigned count,head;bool live; } queue;
int32_t osKernelLock(void) { if (locked) return 1;locked=1;return 0; }
int32_t osKernelUnlock(void) { assert(locked==1);locked=0;++unlock_count;return 1; }
uint32_t osKernelGetTickCount(void) { return tick; }
uint32_t osKernelGetTickFreq(void) { return hz; }
osStatus_t osDelay(uint32_t ticks) {
    int i;SbAppStatus status;
    assert(ticks>0);tick+=running?1000:ticks;
    if (probing) longjmp(gate_escape,1);
    if (running) return ++delays>=12?osError:osOK;
    sb_app_status(&status);
    if (status.state==SB_FAILED && !timeout_exit) for(i=0;i<thread_count;++i) if (!ran[i]) {
        ran[i]=true;functions[i](NULL);
    }
    return osOK;
}
osThreadId_t osThreadNew(osThreadFunc_t func,void *arg,const osThreadAttr_t *attr) {
    SbSnapshot snap;SbAppStatus state;
    (void)arg; assert(attr->stack_size==4096);
    assert(attr->priority==(thread_count==0?osPriorityAboveNormal:thread_count==1?osPriorityNormal:osPriorityBelowNormal));
    if (fail_thread==thread_count+1) return NULL;
    sb_app_status(&state);assert(state.state==SB_BOOTSTRAP);
    sb_pipeline_snapshot(&snap);assert(snap.processed==0);
    if (gate_probe) {
        probing=1;
        if (!setjmp(gate_escape)) { func(NULL);assert(0); }
        probing=0;assert(queue.count==0);
    }
    functions[thread_count++]=func;return (void *)(uintptr_t)thread_count;
}
osMessageQueueId_t osMessageQueueNew(uint32_t count,uint32_t size,const void *attr) {
    (void)attr;assert(count==8 && size==sizeof(SbSensorFrame));
    if (fail_queue) return NULL;queue.live=true;return &queue;
}
osStatus_t osMessageQueuePut(osMessageQueueId_t q,const void *item,uint8_t priority,uint32_t timeout) {
    (void)priority;assert(q==&queue && queue.live && timeout==0);
    if (queue.count==8) return osErrorResource;
    queue.frames[(queue.head+queue.count)%8]=*(const SbSensorFrame *)item;++queue.count;return osOK;
}
osStatus_t osMessageQueueGet(osMessageQueueId_t q,void *out,uint8_t *priority,uint32_t timeout) {
    (void)priority;(void)timeout;assert(q==&queue && queue.live);
    if (!queue.count) return osErrorTimeout;
    *(SbSensorFrame *)out=queue.frames[queue.head];queue.head=(queue.head+1)%8;--queue.count;return osOK;
}
osStatus_t osMessageQueueDelete(osMessageQueueId_t q) {
    SbAppStatus state;sb_app_status(&state);
    assert(q==&queue && queue.live);assert(state.exited==state.created);
    queue.live=false;++deleted;return osOK;
}
static void rules_test(void) {
    SbSensorFrame f={0};SbEventCandidate e;
    sb_event_buffer_init();sb_output_init();sb_pipeline_init();tick=0;sb_clock_init();
#ifdef SB_ROLE_ENV
    f.role=SB_ENV;f.kind=SB_INPUT_ENV;f.source=SB_SOURCE_REAL;f.valid=true;
    f.value.env.temperature_c=9;f.value.env.humidity_pct=50;f.value.env.light_lux=10;
    for(unsigned i=0;i<3;++i){f.clock.uptime_ms=1000+i*1000;assert(sb_rules_process(&f)==SB_OK);}
    assert(sb_event_buffer_get(&e)==SB_OK&&e.type==SB_EVENT_ENV_HIGH&&e.level==SB_LEVEL_WARNING);
    assert(strstr(e.data_json,"\"metric\":\"temp_c\""));
    f.valid=false;f.clock.uptime_ms=4000;assert(sb_rules_process(&f)==SB_OK);
    f.valid=true;f.value.env.temperature_c=7;
    for(unsigned i=0;i<3;++i){f.clock.uptime_ms=5000+i*1000;assert(sb_rules_process(&f)==SB_OK);}
    assert(sb_event_buffer_get(&e)==SB_OK&&e.type==SB_EVENT_ENV_RECOVERED&&e.level==SB_LEVEL_INFO);
    assert(sb_event_buffer_get(&e)==SB_NO_DATA);
    assert(sb_rules_box_state(SB_DOOR_CLOSED,false,"closed-for-sentry")==SB_OK);
    f.value.env.light_lux=10;
    for(unsigned i=0;i<5;++i){f.clock.uptime_ms=8000+i*1000;assert(sb_rules_process(&f)==SB_OK);}
    tick=6000;assert(sb_clock_init()==SB_OK);assert(sb_rules_set_sentry(1,true,0)==SB_OK);
    tick+=3000;SbClock now;assert(sb_clock_get(&now)==SB_OK&&sb_rules_poll(now.uptime_ms)==SB_OK);
    SbRulesStatus armed_status;assert(sb_rules_status(&armed_status)==SB_OK&&armed_status.sentry_armed);
    f.value.env.light_lux=100;f.clock.uptime_ms=now.uptime_ms+20;
    assert(sb_rules_process(&f)==SB_OK);
    SbOutputState light,fan;assert(sb_output_get(SB_LIGHT,&light)==SB_OK&&light.on);
    assert(sb_output_get(SB_FAN,&fan)==SB_OK&&!fan.on);
    assert(sb_event_buffer_get(&e)==SB_OK&&e.type==SB_EVENT_SENTRY_INTRUSION&&e.level==SB_LEVEL_CRITICAL);
    assert(strstr(e.data_json,"\"reason\":\"LIGHT\""));
    tick+=10000;assert(sb_output_poll()==SB_OK&&sb_output_get(SB_LIGHT,&light)==SB_OK&&!light.on);
#else
    SbClock now;SbOutputState output;
    f.role=SB_POSE;f.kind=SB_INPUT_POSE;f.source=SB_SOURCE_REAL;f.valid=true;f.value.pose.z_g=1.0f;
    for(unsigned i=0;i<=150;++i){f.clock.uptime_ms=1000+i*20;assert(sb_rules_process(&f)==SB_OK);}
    SbRulesStatus status;assert(sb_rules_status(&status)==SB_OK&&status.calibrated);
    double radians=50.0*3.141592653589793/180.0;f.value.pose.x_g=(float)sin(radians);f.value.pose.z_g=(float)cos(radians);
    for(unsigned i=0;i<12;++i){f.clock.uptime_ms=5000+i*20;assert(sb_rules_process(&f)==SB_OK);}
    assert(sb_event_buffer_get(&e)==SB_OK&&e.type==SB_EVENT_POSE_CHANGED&&e.level==SB_LEVEL_INFO);
    assert(sb_rules_status(&status)==SB_OK&&status.pose_state==2&&status.tilt_deg>49&&status.tilt_deg<51);
    /* A fast impact while armed starts only the role's lamp for ten seconds. */
    tick=6000;assert(sb_clock_init()==SB_OK);assert(sb_rules_set_sentry(1,true,0)==SB_OK);
    tick+=3000;assert(sb_clock_get(&now)==SB_OK&&sb_rules_poll(now.uptime_ms)==SB_OK);
    f.value.pose.x_g=0;f.value.pose.y_g=0;f.value.pose.z_g=1.6f;f.clock.uptime_ms=now.uptime_ms+20;
    assert(sb_rules_process(&f)==SB_OK);assert(sb_output_get(SB_INDICATOR,&output)==SB_OK&&output.on);
    assert(sb_event_buffer_get(&e)==SB_OK&&e.type==SB_EVENT_SENTRY_INTRUSION&&e.level==SB_LEVEL_CRITICAL);
    assert(sb_rules_poll(f.clock.uptime_ms+1001)==SB_OK);
    assert(sb_event_buffer_get(&e)==SB_OK&&e.type==SB_EVENT_IMPACT&&e.level==SB_LEVEL_WARNING);
    assert(strstr(e.data_json,"\"duration_ms\":0"));
    tick+=10000;assert(sb_output_poll()==SB_OK&&sb_output_get(SB_INDICATOR,&output)==SB_OK&&!output.on);
    assert(sb_rules_set_geofence(31.2304,121.4737,50)==SB_OK);
    memset(&f,0,sizeof(f));f.role=SB_POSE;f.kind=SB_INPUT_LOCATION;f.source=SB_SOURCE_MOCK;f.valid=true;
    f.value.location.latitude_deg=31.2314;f.value.location.longitude_deg=121.4737;
    for(unsigned i=1;i<=3;++i){f.sample_seq=i;f.clock.uptime_ms=12000+i*1000;assert(sb_rules_process(&f)==SB_OK);}
    assert(sb_event_buffer_get(&e)==SB_OK&&e.type==SB_EVENT_GEOFENCE_EXIT&&e.level==SB_LEVEL_WARNING);
    assert(strstr(e.data_json,"\"lat\":31.2314000")&&strstr(e.data_json,"\"lon\":121.4737000"));
    f.value.location.latitude_deg=31.2304;
    for(unsigned i=4;i<=6;++i){f.sample_seq=i;f.clock.uptime_ms=12000+i*1000;assert(sb_rules_process(&f)==SB_OK);}
    assert(sb_event_buffer_get(&e)==SB_OK&&e.type==SB_EVENT_GEOFENCE_ENTER&&e.level==SB_LEVEL_INFO);
#endif
}
static void components(void) {
    SbClock now;SbSensorFrame first,next;SbSnapshot snapshot;
    SbEventCandidate item={0},got;SbOutputState output;SbOutputRequest request;
    uint32_t ticks;uint64_t due,missed;int i,unlocks;
    const SbConfig *c=sb_config_get();
    assert(sb_config_validate()==SB_OK);
    assert(strcmp(c->box_id,"BOX-0001")==0);
#ifdef SB_ROLE_ENV
    assert(c->role==SB_ENV&&c->capabilities==71&&c->sample_ms==1000);
    assert(strcmp(c->node_id,"BOX-0001-ENV")==0);
#else
    assert(c->role==SB_POSE&&c->capabilities==120&&c->sample_ms==20&&c->location_ms==1000);
    assert(strcmp(c->node_id,"BOX-0001-POSE")==0);
#endif
    hz=0;assert(sb_clock_init()==SB_PLATFORM_ERROR);hz=1000;tick=UINT32_MAX-2;
    assert(sb_clock_init()==SB_OK);assert(sb_clock_get(&now)==SB_OK);uint64_t before=now.uptime_ms;
    tick=3;assert(sb_clock_get(&now)==SB_OK&&now.uptime_ms==before+6);
    assert(!now.utc_valid&&now.utc_ms==0&&now.quality==SB_UNSYNCED);
    assert(sb_clock_get(NULL)==SB_INVALID_ARG);
    assert(sb_ms_to_ticks(1,100,&ticks)==SB_OK&&ticks==1);
    assert(sb_ms_to_ticks(0,100,&ticks)==SB_OK&&ticks==0);
    assert(sb_ms_to_ticks(1,0,&ticks)==SB_PLATFORM_ERROR);
    assert(sb_ms_to_ticks(UINT64_MAX,1000,&ticks)==SB_PLATFORM_ERROR);
    assert(sb_ms_to_ticks(UINT32_MAX,2000,&ticks)==SB_PLATFORM_ERROR);
    locked=1;unlocks=unlock_count;assert(sb_clock_get(&now)==SB_OK&&locked==1&&unlock_count==unlocks);locked=0;
    due=100;missed=0;assert(sb_schedule_advance(165,20,&due,&missed)==SB_OK&&due==180&&missed==3);
    assert(sb_schedule_advance(UINT64_MAX,20,&due,&missed)==SB_PLATFORM_ERROR);
    tick=0;assert(sb_clock_init()==SB_OK);assert(sb_sensor_init()==SB_OK);
    SbInputKind kind=c->role==SB_ENV?SB_INPUT_ENV:SB_INPUT_POSE;
    assert(sb_sensor_read(kind,&first)==SB_OK&&first.valid&&first.source==SB_SOURCE_MOCK);
    assert(sb_sensor_read(kind,&next)==SB_OK&&next.valid);
    assert(sb_sensor_read(kind,&next)==SB_OK&&next.valid);
    assert(sb_sensor_read(kind,&next)==SB_OK&&!next.valid);
    assert(sb_sensor_init()==SB_OK&&sb_sensor_read(kind,&next)==SB_OK);
    assert(memcmp(&first,&next,sizeof(first))==0);
    assert(sb_sensor_read(c->role==SB_ENV?SB_INPUT_POSE:SB_INPUT_ENV,&next)==SB_UNSUPPORTED_CAP);
    if(c->role==SB_POSE){
        assert(sb_sensor_location_period_ms()==10000);
        assert(sb_sensor_read(SB_INPUT_LOCATION,&next)==SB_OK&&next.sample_seq==1);
        assert(sb_sensor_set_gps_mode(SB_GPS_ROUTE,7)==SB_OK&&sb_sensor_location_period_ms()==1000);
        assert(sb_sensor_read(SB_INPUT_LOCATION,&next)==SB_OK&&next.sample_seq==2&&next.source==SB_SOURCE_MOCK);
        sb_sensor_init();
    }
    sb_pipeline_init();assert(sb_pipeline_process(&first)==SB_OK);
    assert(sb_pipeline_snapshot(&snapshot)==SB_OK&&snapshot.processed==1);
    snapshot.frames[kind].valid=false;assert(sb_pipeline_snapshot(&snapshot)==SB_OK&&snapshot.frames[kind].valid);
    first.valid=false;assert(sb_pipeline_process(&first)==SB_OK);
    assert(sb_pipeline_snapshot(&snapshot)==SB_OK&&!snapshot.frames[kind].valid);
    assert(sb_output_init()==SB_OK);
    request.kind=c->role==SB_ENV?SB_LIGHT:SB_INDICATOR;request.on=true;request.duration_ms=50;
    assert(sb_output_get(request.kind,&output)==SB_OK&&!output.on);
    assert(sb_output_set(&request)==SB_OK&&sb_output_get(request.kind,&output)==SB_OK&&output.on);
    tick=50;assert(sb_output_poll()==SB_OK&&sb_output_get(request.kind,&output)==SB_OK&&!output.on);
    request.kind=c->role==SB_ENV?SB_INDICATOR:SB_FAN;assert(sb_output_set(&request)==SB_UNSUPPORTED_CAP);
    assert(sb_event_buffer_init()==SB_OK&&sb_event_buffer_get(&got)==SB_NO_DATA);
    for(i=0;i<16;++i){item.diagnostic_code=(uint32_t)i;assert(sb_event_buffer_put(&item)==SB_OK);}
    assert(sb_event_buffer_put(&item)==SB_FULL&&sb_event_buffer_rejected()==1);
    assert(sb_network_poll()==SB_NOT_IMPLEMENTED&&sb_network_publish(&item)==SB_NOT_IMPLEMENTED);
    assert(sb_network_publish(NULL)==SB_INVALID_ARG);
    for(i=0;i<16;++i){assert(sb_event_buffer_get(&got)==SB_OK&&got.diagnostic_code==(uint32_t)i);}
    assert(sb_event_buffer_get(&got)==SB_NO_DATA);
    rules_test();
    printf("components: role=%d; clock/mock/outputs/snapshot/buffer/network OK\n",c->role);
}
int main(int argc,char **argv) {
    SbAppStatus state;int mode=argc>1?atoi(argv[1]):-1;
    if(mode<0){components();return 0;}
    if(mode==1)hz=0;
    if(mode==2)fail_queue=1;
    if(mode>=3&&mode<=5)fail_thread=mode-2;
    if(mode==6){fail_thread=3;timeout_exit=1;}
    if(mode==7)gate_probe=1;
    SbResult result=sb_app_start();sb_app_status(&state);
    if(mode>=1&&mode<=6){
        assert(result==SB_PLATFORM_ERROR&&state.state==SB_FAILED);
        if(mode==6){assert(state.reset_required&&queue.live&&!deleted);}
        else {assert(!state.reset_required&&!queue.live);assert(state.exited==state.created);}
    }else{
        assert(result==SB_OK&&state.state==SB_READY&&state.created==3);
        assert(sb_app_start()==SB_INVALID_ARG);
        running=1;delays=0;
        if(mode==8){
            SbEventCandidate candidate={0};SbEventCandidate got;
            assert(sb_event_buffer_put(&candidate)==SB_OK);
            functions[2](NULL);assert(sb_event_buffer_get(&got)==SB_OK);
            sb_app_status(&state);assert(state.network_disabled==12);
        }else{
            /* A stalled processor causes nonblocking rejection at the eighth frame. */
            functions[0](NULL);
            sb_app_status(&state);
            assert(state.sample_queue_full>0);
            if(sb_config_get()->role==SB_POSE)assert(state.missed_periods>0);
        }
    }
    assert(!locked);printf("lifecycle mode=%d OK\n",mode);return 0;
}
