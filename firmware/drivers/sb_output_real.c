#include "sb_config.h"
#include "sb_hardware.h"
#include <string.h>
static SbOutputState states[SB_OUTPUT_COUNT];
SbResult sb_output_init(void) {
    if (sb_hw_gpio_init()!=SB_OK) return SB_PLATFORM_ERROR;
    int32_t lock=sb_platform_lock(); if (lock<0) return SB_PLATFORM_ERROR;
    memset(states,0,sizeof(states)); sb_platform_unlock(lock); return SB_OK;
}
static SbResult supported(SbOutputKind kind) {
    if (kind<0||kind>=SB_OUTPUT_COUNT) return SB_INVALID_ARG;
    return (sb_config_get()->role==SB_ENV ? kind!=SB_INDICATOR : kind==SB_INDICATOR)?SB_OK:SB_UNSUPPORTED_CAP;
}
SbResult sb_output_set(const SbOutputRequest *r) {
    SbClock now; SbResult result; int32_t lock;
    if (!r) return SB_INVALID_ARG;
    result=supported(r->kind); if (result!=SB_OK) return result;
    if (r->on && !r->duration_ms) return SB_INVALID_ARG;
    if (sb_clock_get(&now)!=SB_OK || now.uptime_ms>UINT64_MAX-r->duration_ms) return SB_PLATFORM_ERROR;
    lock=sb_platform_lock(); if (lock<0) return SB_PLATFORM_ERROR;
    if(sb_hw_gpio(r->kind,r->on)!=SB_OK) { sb_platform_unlock(lock); return SB_PLATFORM_ERROR; }
    states[r->kind].on=r->on;
    states[r->kind].expires_ms=r->on?now.uptime_ms+r->duration_ms:0;
    sb_platform_unlock(lock); return SB_OK;
}
SbResult sb_output_poll(void) {
    SbClock now; size_t i; int32_t lock;
    if (sb_clock_get(&now)!=SB_OK) return SB_PLATFORM_ERROR;
    lock=sb_platform_lock(); if (lock<0) return SB_PLATFORM_ERROR;
    for (i=0;i<SB_OUTPUT_COUNT;++i) if (states[i].on && now.uptime_ms>=states[i].expires_ms) {
        if(sb_hw_gpio((SbOutputKind)i,false)!=SB_OK) { sb_platform_unlock(lock); return SB_PLATFORM_ERROR; }
        states[i].on=false; states[i].expires_ms=0;
    }
    sb_platform_unlock(lock); return SB_OK;
}
SbResult sb_output_get(SbOutputKind kind,SbOutputState *out) {
    SbResult result=supported(kind); int32_t lock;
    if (!out) return SB_INVALID_ARG;
    if (result!=SB_OK) return result;
    if (sb_output_poll()!=SB_OK) return SB_PLATFORM_ERROR;
    lock=sb_platform_lock(); if (lock<0) return SB_PLATFORM_ERROR;
    *out=states[kind]; sb_platform_unlock(lock); return SB_OK;
}
