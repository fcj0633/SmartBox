#include "sb_rule_control.h"
#include <string.h>
#define SLOTS 8u
typedef enum { FREE, QUEUED, RUNNING, WAITING, DONE } Phase;
typedef struct { Phase phase; SbRuleCommand command; SbRuleResult result; } Slot;
static Slot slots[SLOTS];
static SbRulesStatus published;
static uint32_t serial;
static uint64_t rejected;
void sb_control_init(void) {
    memset(slots,0,sizeof(slots)); serial=0;rejected=0;
    (void)sb_rules_status(&published);
}
SbResult sb_control_submit(const SbRuleCommand *command,uint32_t *token) {
    unsigned i; int32_t lock;
    if(!command||!token||command->kind<SB_CONTROL_PROFILE||command->kind>SB_CONTROL_BOX)return SB_INVALID_ARG;
    lock=sb_platform_lock();if(lock<0)return SB_PLATFORM_ERROR;
    for(i=0;i<SLOTS;++i)if(slots[i].phase==FREE)break;
    if(i==SLOTS||serial==UINT32_MAX){++rejected;sb_platform_unlock(lock);return SB_FULL;}
    slots[i].command=*command;memset(&slots[i].result,0,sizeof(slots[i].result));
    slots[i].result.token=++serial;*token=serial;slots[i].phase=QUEUED;
    sb_platform_unlock(lock);return SB_OK;
}
static SbResult execute(const SbRuleCommand *c) {
    if(c->kind==SB_CONTROL_PROFILE)return sb_rules_apply_profile(c->tl,c->th,c->hl,c->hh,c->t_hyst,c->h_hyst,c->id);
    if(c->kind==SB_CONTROL_GPS)return sb_rules_set_gps_mode(c->mode,c->seed);
    if(c->kind==SB_CONTROL_CALIBRATE)return sb_rules_start_calibration();
    if(c->kind==SB_CONTROL_SENTRY)return sb_rules_set_sentry(c->revision,c->on,c->duration);
    return sb_rules_box_state(c->door,false,c->id);
}
SbResult sb_control_process(void) {
    SbClock now;SbRulesStatus state;unsigned i;int32_t lock;
    if(sb_clock_get(&now)!=SB_OK||sb_rules_poll(now.uptime_ms)!=SB_OK)return SB_PLATFORM_ERROR;
    for(i=0;i<SLOTS;++i){
        SbRuleCommand command;Phase phase;SbRuleResult result;
        lock=sb_platform_lock();if(lock<0)return SB_PLATFORM_ERROR;
        phase=slots[i].phase;command=slots[i].command;result=slots[i].result;
        if(phase==QUEUED)slots[i].phase=RUNNING;
        sb_platform_unlock(lock);
        if(phase!=QUEUED&&phase!=WAITING)continue;
        bool done=false;
        if(command.expires_utc_ms&&(!now.utc_valid||(uint64_t)now.utc_ms>=command.expires_utc_ms)){
            result.expired=now.utc_valid;result.result=SB_INVALID_ARG;done=true;
        } else if(phase==QUEUED){
            result.result=execute(&command);
            done=result.result!=SB_OK||command.kind==SB_CONTROL_PROFILE||command.kind==SB_CONTROL_GPS||command.kind==SB_CONTROL_BOX||(command.kind==SB_CONTROL_SENTRY&&!command.on);
        }
        if(sb_rules_status(&state)!=SB_OK)return SB_PLATFORM_ERROR;
        if(!done&&phase==WAITING){
            if(command.kind==SB_CONTROL_CALIBRATE&&state.calibrated)done=true;
            if(command.kind==SB_CONTROL_SENTRY&&command.on&&state.sentry_armed&&state.sentry_revision==command.revision)done=true;
            if(command.kind==SB_CONTROL_SENTRY&&state.sentry_revision>command.revision){done=true;result.result=SB_INVALID_ARG;}
        }
        result.state=state;
        lock=sb_platform_lock();if(lock<0)return SB_PLATFORM_ERROR;
        slots[i].result=result;
        slots[i].phase=command.kind==SB_CONTROL_BOX?FREE:done?DONE:WAITING;
        sb_platform_unlock(lock);
    }
    if(sb_rules_status(&state)!=SB_OK)return SB_PLATFORM_ERROR;
    lock=sb_platform_lock();if(lock<0)return SB_PLATFORM_ERROR;
    published=state;sb_platform_unlock(lock);return SB_OK;
}
SbResult sb_control_take(uint32_t token,SbRuleResult *out) {
    unsigned i;int32_t lock;if(!out||!token)return SB_INVALID_ARG;
    lock=sb_platform_lock();if(lock<0)return SB_PLATFORM_ERROR;
    for(i=0;i<SLOTS;++i)if(slots[i].phase!=FREE&&slots[i].result.token==token){
        if(slots[i].phase!=DONE){sb_platform_unlock(lock);return SB_NO_DATA;}
        *out=slots[i].result;slots[i].phase=FREE;sb_platform_unlock(lock);return SB_OK;
    }
    sb_platform_unlock(lock);return SB_NO_DATA;
}
SbResult sb_control_status(SbRulesStatus *out) {
    int32_t lock;if(!out)return SB_INVALID_ARG;
    lock=sb_platform_lock();if(lock<0)return SB_PLATFORM_ERROR;
    *out=published;sb_platform_unlock(lock);return SB_OK;
}
uint64_t sb_control_rejected(void) {
    uint64_t value;int32_t lock=sb_platform_lock();if(lock<0)return 0;
    value=rejected;sb_platform_unlock(lock);return value;
}
