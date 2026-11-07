#include "sb_config.h"
#include <string.h>
#ifdef SB_HARDWARE
#define SB_LOC_MS 10000
#else
#define SB_LOC_MS 1000
#endif
#ifdef SB_ROLE_ENV
static const SbConfig config = {SB_ENV,"BOX-0001","BOX-0001-ENV",71,1000,0};
#else
static const SbConfig config = {SB_POSE,"BOX-0001","BOX-0001-POSE",120,20,SB_LOC_MS};
#endif
const SbConfig *sb_config_get(void) { return &config; }
static bool valid_id(const char *s) {
    size_t n=0; if (!s) return false;
    for (;s[n];++n) { char c=s[n]; if (n>=48 || !((c>='A'&&c<='Z') ||
        (c>='a'&&c<='z') || (c>='0'&&c<='9') || c=='-' || c=='_')) return false; }
    return n>0;
}
SbResult sb_config_validate(void) {
    if (!valid_id(config.node_id)||!valid_id(config.box_id)) return SB_INVALID_ARG;
    if (config.role==SB_ENV && config.capabilities==71 && config.sample_ms==1000 && !config.location_ms) return SB_OK;
    if (config.role==SB_POSE && config.capabilities==120 && config.sample_ms==20 && config.location_ms==SB_LOC_MS) return SB_OK;
    return SB_INVALID_ARG;
}
