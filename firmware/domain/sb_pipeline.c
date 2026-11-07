#include "sb_config.h"
#include "sb_ports.h"
#include <string.h>
static SbSnapshot snapshot;
void sb_pipeline_init(void) { memset(&snapshot,0,sizeof(snapshot)); }
SbResult sb_pipeline_process(const SbSensorFrame *frame) {
    int32_t lock;
    if (!frame||frame->kind<0||frame->kind>=SB_INPUT_COUNT||(frame->source!=SB_SOURCE_MOCK && frame->source!=SB_SOURCE_REAL)) return SB_INVALID_ARG;
    if (frame->role!=sb_config_get()->role ||
        ((frame->role==SB_ENV)!=(frame->kind==SB_INPUT_ENV))) return SB_UNSUPPORTED_CAP;
    lock=sb_platform_lock(); if (lock<0) return SB_PLATFORM_ERROR;
    snapshot.frames[frame->kind]=*frame; snapshot.present[frame->kind]=true; ++snapshot.processed;
    sb_platform_unlock(lock);
    /* Future rule port goes here. No alert/door/sentry conclusion in this task. */
    return SB_OK;
}
SbResult sb_pipeline_snapshot(SbSnapshot *out) {
    int32_t lock;
    if (!out) return SB_INVALID_ARG;
    lock=sb_platform_lock(); if (lock<0) return SB_PLATFORM_ERROR;
    *out=snapshot; sb_platform_unlock(lock); return SB_OK;
}
