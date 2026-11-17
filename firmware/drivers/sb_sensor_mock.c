#include "sb_config.h"
#include "sb_ports.h"
#include <string.h>
static uint32_t indices[SB_INPUT_COUNT];
static SbGpsMode gps_mode=SB_GPS_FIXED;static uint32_t gps_seed=1;
static uint32_t location_seq;
SbResult sb_sensor_init(void) { memset(indices,0,sizeof(indices));location_seq=0;gps_mode=SB_GPS_FIXED;gps_seed=1;return SB_OK; }
SbResult sb_sensor_set_gps_mode(SbGpsMode mode,uint32_t seed){if(mode<SB_GPS_FIXED||mode>SB_GPS_WALK)return SB_INVALID_ARG;gps_mode=mode;gps_seed=seed?seed:1;return SB_OK;}
uint32_t sb_sensor_location_period_ms(void){return gps_mode==SB_GPS_FIXED?10000:1000;}
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
        if(location_seq==UINT32_MAX){out->valid=false;return SB_OK;}
        out->sample_seq=++location_seq;
        uint32_t n=indices[kind]+gps_seed;
        if(gps_mode==SB_GPS_FIXED){out->value.location.latitude_deg=31.2304;out->value.location.longitude_deg=121.4737;}
        else {int noise;uint32_t sample_seed;
            if(gps_mode==SB_GPS_WALK)gps_seed=gps_seed*1664525u+1013904223u;
            sample_seed=gps_mode==SB_GPS_WALK?gps_seed:gps_seed+n*7919u;
            noise=3+(int)((sample_seed>>16)%3);if(sample_seed&1)noise=-noise;
            out->value.location.latitude_deg=31.2304+(gps_mode==SB_GPS_ROUTE?0.0001*(n%5):0)+(double)noise/111320.0;
            out->value.location.longitude_deg=121.4737+(gps_mode==SB_GPS_ROUTE?0.00008*(n%5):0)+(double)noise/95100.0;}
    }
    return SB_OK;
}
