#include "sb_config.h"
#include "sb_ports.h"
#include "cmsis_os2.h"
#include <string.h>
static SbAppStatus status;
static osMessageQueueId_t samples;
static bool started;
static bool state_is(SbStartupState value) {
    bool result; int32_t lock=sb_platform_lock();
    if (lock<0) return false;
    result=status.state==value; sb_platform_unlock(lock); return result;
}
static void fail(const char *why) {
    int32_t lock=sb_platform_lock();
    if (lock<0) return;
    status.failure=why; status.state=SB_FAILED; sb_platform_unlock(lock);
    (void)sb_output_init(); /* Mock fail-safe: no output remains latched on. */
}
static bool pause_ms(uint32_t ms) {
    uint32_t ticks;
    return sb_ms_to_ticks(ms,sb_platform_frequency(),&ticks)==SB_OK && ticks && osDelay(ticks)==osOK;
}
static bool enter(void) {
    while (state_is(SB_BOOTSTRAP)) if (!pause_ms(10)) { fail("startup delay"); break; }
    return state_is(SB_READY);
}
static void exited(void) {
    int32_t lock=sb_platform_lock(); if (lock<0) return;
    ++status.exited; sb_platform_unlock(lock);
}
static void add(uint64_t *counter,uint64_t value) {
    int32_t lock=sb_platform_lock(); if (lock<0) return;
    *counter+=value; sb_platform_unlock(lock);
}
static void sample_one(SbInputKind kind) {
    SbSensorFrame frame; SbResult result=sb_sensor_read(kind,&frame);
    if (result!=SB_OK) { fail("sample port"); return; }
    if (osMessageQueuePut(samples,&frame,0,0)!=osOK) add(&status.sample_queue_full,1);
}
static void sampling(void *arg) {
    SbClock now; uint64_t sample_due=0,location_due=0,missed; const SbConfig *c=sb_config_get();
    (void)arg;
    if (enter() && sb_clock_get(&now)==SB_OK) { sample_due=location_due=now.uptime_ms; }
    while (state_is(SB_READY)) {
        if (sb_clock_get(&now)!=SB_OK) { fail("clock"); break; }
        if (now.uptime_ms>=sample_due) {
            sample_one(c->role==SB_ENV?SB_INPUT_ENV:SB_INPUT_POSE); missed=0;
            if (sb_schedule_advance(now.uptime_ms,c->sample_ms,&sample_due,&missed)!=SB_OK) { fail("sample deadline"); break; }
            add(&status.missed_periods,missed);
        }
        if (c->role==SB_POSE && now.uptime_ms>=location_due) {
            sample_one(SB_INPUT_LOCATION); missed=0;
            if (sb_schedule_advance(now.uptime_ms,c->location_ms,&location_due,&missed)!=SB_OK) { fail("location deadline"); break; }
            add(&status.missed_periods,missed);
        }
        if (!pause_ms(c->role==SB_ENV?10:1)) { fail("sample delay"); break; }
    }
    exited(); /* Returning is required: SDK osThreadExit is an empty function. */
}
static void processing(void *arg) {
    SbSensorFrame frame; uint32_t wait;
    (void)arg;
    if (enter()) while (state_is(SB_READY)) {
        if (sb_ms_to_ticks(20,sb_platform_frequency(),&wait)!=SB_OK) { fail("queue wait"); break; }
        osStatus_t result=osMessageQueueGet(samples,&frame,NULL,wait);
        if (result==osOK) {
            if (sb_pipeline_process(&frame)!=SB_OK) { fail("pipeline"); break; }
        } else if (result!=osErrorTimeout && result!=osErrorResource) { fail("queue read"); break; }
        if (sb_output_poll()!=SB_OK) { fail("output clock"); break; }
    }
    exited();
}
static void networking(void *arg) {
    (void)arg;
    if (enter()) while (state_is(SB_READY)) {
        if (sb_network_poll()==SB_NOT_IMPLEMENTED) add(&status.network_disabled,1);
        /* Disabled network never consumes the volatile candidate buffer. */
        if (!pause_ms(100)) { fail("network delay"); break; }
    }
    exited();
}
void sb_app_status(SbAppStatus *out) {
    int32_t lock;
    if (!out) return;
    lock=sb_platform_lock(); if (lock<0) { memset(out,0,sizeof(*out)); out->state=SB_FAILED; out->reset_required=true; return; }
    *out=status; sb_platform_unlock(lock);
    out->candidate_full=sb_event_buffer_rejected();
}
static SbResult cleanup(void) {
    unsigned attempt; SbAppStatus current;
    for (attempt=0;attempt<100;++attempt) {
        sb_app_status(&current);
        if (current.exited==current.created) break;
        if (!pause_ms(10)) break;
    }
    sb_app_status(&current);
    if (current.exited==current.created) {
        if (samples && osMessageQueueDelete(samples)!=osOK) status.reset_required=true;
        else samples=NULL;
    } else status.reset_required=true; /* Retain resources: no use-after-free. */
    (void)sb_output_init();
    return SB_PLATFORM_ERROR;
}
SbResult sb_app_start(void) {
    static const osThreadFunc_t functions[]={sampling,processing,networking};
    static const char *names[]={"sb_sample","sb_process","sb_network"};
    static const osPriority_t priorities[]={osPriorityAboveNormal,osPriorityNormal,osPriorityBelowNormal};
    unsigned i;
    if (started) return SB_INVALID_ARG;
    started=true; memset(&status,0,sizeof(status)); status.state=SB_BOOTSTRAP;
    if (sb_config_validate()!=SB_OK) { fail("config"); return cleanup(); }
    if (sb_clock_init()!=SB_OK) { fail("clock init"); return cleanup(); }
    if (sb_sensor_init()!=SB_OK || sb_output_init()!=SB_OK || sb_event_buffer_init()!=SB_OK) { fail("port init"); return cleanup(); }
    sb_pipeline_init();
    samples=osMessageQueueNew(8,sizeof(SbSensorFrame),NULL);
    if (!samples) { fail("sample queue"); return cleanup(); }
    for (i=0;i<3;++i) {
        osThreadAttr_t attr={0}; attr.name=names[i]; attr.stack_size=4096; attr.priority=priorities[i];
        if (!osThreadNew(functions[i],NULL,&attr)) { fail(names[i]); return cleanup(); }
        ++status.created;
    }
    { int32_t lock=sb_platform_lock();
      if (lock<0) { fail("ready lock"); return cleanup(); }
      status.state=SB_READY; sb_platform_unlock(lock); }
    return SB_OK;
}
