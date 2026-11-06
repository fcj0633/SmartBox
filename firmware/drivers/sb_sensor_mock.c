#include "sb_config.h"
#include "sb_ports.h"
#include <string.h>
static uint32_t indices[SB_INPUT_COUNT];
SbResult sb_sensor_init(void) { memset(indices,0,sizeof(indices)); return SB_OK; }
SbResult sb_sensor_read(SbInputKind kind,SbSensorFrame *out) {
    uint32_t i; const SbConfig *c=sb_config_get();
    if (!out || kind<0 || kind>=SB_INPUT_COUNT) return SB_INVALID_ARG;
    if ((c->role==SB_ENV && kind!=SB_INPUT_ENV) || (c->role==SB_POSE && kind==SB_INPUT_ENV))
        return SB_UNSUPPORTED_CAP;
    memset(out,0,sizeof(*out));
    if (sb_clock_get(&out->clock)!=SB_OK) return SB_PLATFORM_ERROR;
    i=indices[kind]++%4; out->role=c->role; out->kind=kind; out->source=SB_SOURCE_MOCK;
    out->valid=i!=3;
    if (kind==SB_INPUT_ENV) {
        out->value.env.temperature_c=22.0f+(float)i;
        out->value.env.humidity_pct=50.0f+(float)i;
        out->value.env.light_lux=100.0f+(float)(i*50);
    } else if (kind==SB_INPUT_POSE) {
        out->value.pose.x_g=0.1f*(float)i; out->value.pose.z_g=1.0f;
        out->value.pose.tilt_deg=5.0f*(float)i; /* Fixture observation, no estimator. */
    } else {
        out->value.location.latitude_deg=31.2304+0.0001*i;
        out->value.location.longitude_deg=121.4737+0.0001*i;
    }
    return SB_OK;
}
