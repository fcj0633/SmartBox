#include "sb_ports.h"
#include <string.h>
#include <limits.h>
static uint32_t last_tick, frequency;
static uint64_t extended;
static bool initialized;
SbResult sb_ms_to_ticks(uint64_t ms,uint32_t hz,uint32_t *out) {
    uint64_t ticks;
    if (!out) return SB_INVALID_ARG;
    if (!hz || ms>(UINT64_MAX-999)/hz) return SB_PLATFORM_ERROR;
    ticks=(ms*hz+999)/1000;
    if (ticks>UINT32_MAX) return SB_PLATFORM_ERROR;
    *out=(uint32_t)ticks; return SB_OK;
}
SbResult sb_clock_init(void) {
    int32_t lock=sb_platform_lock();
    if (lock<0) return SB_PLATFORM_ERROR;
    initialized=false; frequency=sb_platform_frequency(); last_tick=sb_platform_tick();
    /* Preserve time since boot, not time since this module was initialized. */
    extended=last_tick; initialized=frequency!=0;
    sb_platform_unlock(lock); return initialized?SB_OK:SB_PLATFORM_ERROR;
}
SbResult sb_clock_get(SbClock *out) {
    uint32_t tick; uint64_t seconds, remainder; int32_t lock;
    if (!out) return SB_INVALID_ARG;
    lock=sb_platform_lock(); if (lock<0) return SB_PLATFORM_ERROR;
    if (!initialized) { sb_platform_unlock(lock); return SB_PLATFORM_ERROR; }
    tick=sb_platform_tick();
    if (extended>UINT64_MAX-(uint32_t)(tick-last_tick)) {
        sb_platform_unlock(lock); return SB_PLATFORM_ERROR;
    }
    extended+=(uint32_t)(tick-last_tick); last_tick=tick;
    seconds=extended/frequency; remainder=extended%frequency;
    if (seconds>UINT64_MAX/1000) { sb_platform_unlock(lock); return SB_PLATFORM_ERROR; }
    memset(out,0,sizeof(*out));
    out->uptime_ms=seconds*1000+(remainder*1000)/frequency;
    out->quality=SB_UNSYNCED; /* utc_valid=false; utc_ms has no meaning. */
    sb_platform_unlock(lock); return SB_OK;
}
SbResult sb_schedule_advance(uint64_t now,uint32_t period,uint64_t *deadline,uint64_t *missed) {
    uint64_t steps;
    if (!period||!deadline||!missed) return SB_INVALID_ARG;
    if (*deadline>now) return SB_OK;
    steps=(now-*deadline)/period;
    if (steps==UINT64_MAX || steps+1>(UINT64_MAX-*deadline)/period || *missed>UINT64_MAX-steps)
        return SB_PLATFORM_ERROR;
    *deadline+=(steps+1)*period; *missed+=steps; return SB_OK;
}
