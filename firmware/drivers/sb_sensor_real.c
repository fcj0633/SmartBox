/* Register configuration derived from BearPi C2/C4 examples (Apache-2.0,
 * Copyright 2020 Nanjing Xiaoxiongpai Intelligent Technology Co., Ltd.).
 * Read errors are propagated; no example globals or blocking fault loops. */
#include "sb_config.h"
#include "sb_hardware.h"
#include <math.h>
#include <string.h>
static bool ready;
static uint32_t loc_seq;
static SbGpsMode gps_mode=SB_GPS_FIXED;static uint32_t gps_seed=1;
SbResult sb_sensor_set_gps_mode(SbGpsMode mode,uint32_t seed){if(mode<SB_GPS_FIXED||mode>SB_GPS_WALK)return SB_INVALID_ARG;gps_mode=mode;gps_seed=seed?seed:1;return SB_OK;}
uint32_t sb_sensor_location_period_ms(void){return gps_mode==SB_GPS_FIXED?10000:1000;}
static SbResult setup(void) {
    const uint8_t light[]={0x01}, continuous[]={0x10}, sht[]={0x22,0x36};
    const uint8_t mpu[][2]={{0x6b,0x80},{0x6b,0},{0x38,0},{0x6a,0},
                           {0x23,0},{0x1a,4},{0x19,19},{0x1c,0x18}};
    unsigned i;
    if (sb_hw_i2c_init()!=SB_OK) return SB_PLATFORM_ERROR;
    if (sb_config_get()->role==SB_ENV) {
        if (sb_hw_write(0x23,light,1)!=SB_OK || sb_hw_write(0x23,continuous,1)!=SB_OK ||
            sb_hw_write(0x44,sht,2)!=SB_OK) return SB_PLATFORM_ERROR;
        sb_hw_delay(180);
    } else {
        for(i=0;i<sizeof(mpu)/sizeof(mpu[0]);++i) {
            if(sb_hw_write(0x68,mpu[i],2)!=SB_OK) return SB_PLATFORM_ERROR;
            if(i==0) sb_hw_delay(20);
        }
    }
    return SB_OK;
}
SbResult sb_sensor_init(void) {
    loc_seq=0; ready=setup()==SB_OK;
    /* An unavailable sensor is a quality fault, not a network/boot failure. */
    return SB_OK;
}
static bool crc(const uint8_t *p) {
    unsigned i,j; uint8_t c=255;
    for(i=0;i<2;++i) { c^=p[i]; for(j=0;j<8;++j) c=(c&128)?(uint8_t)((c<<1)^0x31):(uint8_t)(c<<1); }
    return c==p[2];
}
SbResult sb_sensor_read(SbInputKind kind,SbSensorFrame *out) {
    uint8_t b[6],light[2],reg=0x3b,id,who=0x75,fetch[]={0xe0,0};
    const SbConfig *c=sb_config_get();
    if(!out||kind<0||kind>=SB_INPUT_COUNT) return SB_INVALID_ARG;
    if((c->role==SB_ENV && kind!=SB_INPUT_ENV)||(c->role==SB_POSE && kind==SB_INPUT_ENV)) return SB_UNSUPPORTED_CAP;
    memset(out,0,sizeof(*out)); out->role=c->role; out->kind=kind; out->source=SB_SOURCE_REAL;
    if(sb_clock_get(&out->clock)!=SB_OK) return SB_PLATFORM_ERROR;
    if(kind==SB_INPUT_LOCATION) {
        if(loc_seq==UINT32_MAX){out->valid=false;return SB_OK;}
        out->source=SB_SOURCE_MOCK; out->valid=true;
        uint32_t n=loc_seq+gps_seed;
        if(gps_mode==SB_GPS_FIXED){out->value.location.latitude_deg=31.2304;out->value.location.longitude_deg=121.4737;}
        else {int noise;uint32_t sample_seed;
            if(gps_mode==SB_GPS_WALK)gps_seed=gps_seed*1664525u+1013904223u;
            sample_seed=gps_mode==SB_GPS_WALK?gps_seed:gps_seed+n*7919u;
            noise=3+(int)((sample_seed>>16)%3);if(sample_seed&1)noise=-noise;
            out->value.location.latitude_deg=31.2304+(gps_mode==SB_GPS_ROUTE?0.0001*(n%5):0)+(double)noise/111320.0;
            out->value.location.longitude_deg=121.4737+(gps_mode==SB_GPS_ROUTE?0.00008*(n%5):0)+(double)noise/95100.0;}
        out->sample_seq=++loc_seq; return SB_OK;
    }
    if(!ready) ready=setup()==SB_OK;
    if(!ready) return SB_OK;
    if(kind==SB_INPUT_ENV) {
        if(sb_hw_read(0x23,NULL,0,light,2)!=SB_OK||sb_hw_read(0x44,fetch,2,b,6)!=SB_OK||!crc(b)||!crc(b+3)) return SB_OK;
        out->value.env.temperature_c=-45.0f+175.0f*((b[0]<<8)|b[1])/65535.0f;
        out->value.env.humidity_pct=100.0f*((b[3]<<8)|b[4])/65535.0f;
        out->value.env.light_lux=((light[0]<<8)|light[1])/1.2f; out->valid=true;
    } else {
        if(sb_hw_read(0x68,&who,1,&id,1)!=SB_OK||id!=0x68||sb_hw_read(0x68,&reg,1,b,6)!=SB_OK) return SB_OK;
        out->value.pose.x_g=(int16_t)((b[0]<<8)|b[1])/2048.0f;
        out->value.pose.y_g=(int16_t)((b[2]<<8)|b[3])/2048.0f;
        out->value.pose.z_g=(int16_t)((b[4]<<8)|b[5])/2048.0f;
        double x=out->value.pose.x_g,y=out->value.pose.y_g,z=out->value.pose.z_g,n=sqrt(x*x+y*y+z*z);
        if(n>0.0001) { double ratio=z/n;if(ratio>1)ratio=1;if(ratio< -1)ratio= -1;
            out->value.pose.tilt_deg=(float)(acos(ratio)*57.295779513); out->valid=true; }
    }
    return SB_OK;
}
