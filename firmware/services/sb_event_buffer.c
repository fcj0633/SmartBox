#include "sb_ports.h"
#include <string.h>
#define CAPACITY 16u
static SbEventCandidate items[CAPACITY];
static uint32_t head,count;
static uint64_t rejected;
SbResult sb_event_buffer_init(void) { head=count=0; rejected=0; memset(items,0,sizeof(items)); return SB_OK; }
SbResult sb_event_buffer_put(const SbEventCandidate *item) {
    int32_t lock;
    if (!item) return SB_INVALID_ARG;
    lock=sb_platform_lock(); if (lock<0) return SB_PLATFORM_ERROR;
    if (count==CAPACITY) { ++rejected; sb_platform_unlock(lock); return SB_FULL; }
    items[(head+count)%CAPACITY]=*item; ++count; sb_platform_unlock(lock); return SB_OK;
}
SbResult sb_event_buffer_get(SbEventCandidate *out) {
    int32_t lock;
    if (!out) return SB_INVALID_ARG;
    lock=sb_platform_lock(); if (lock<0) return SB_PLATFORM_ERROR;
    if (!count) { sb_platform_unlock(lock); return SB_NO_DATA; }
    *out=items[head]; head=(head+1)%CAPACITY; --count; sb_platform_unlock(lock); return SB_OK;
}
uint64_t sb_event_buffer_rejected(void) {
    uint64_t value; int32_t lock=sb_platform_lock();
    if (lock<0) return UINT64_MAX;
    value=rejected; sb_platform_unlock(lock); return value;
}
