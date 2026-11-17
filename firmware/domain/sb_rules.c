#include "sb_rules.h"
#include "sb_config.h"
#include <math.h>
#include <stdio.h>
#include <string.h>

/* Rules are deterministic state machines. They never perform network I/O. */
typedef struct {
    char profile_id[49];
    float tl,th,hl,hh,t_hyst,h_hyst;
    int temp_state,humidity_state;
    unsigned temp_in,temp_out,humidity_in,humidity_out;
    bool temp_pending,humidity_pending;
    uint64_t suppressed,events;
    SbDoorState box_door;
    uint64_t suppress_until;
    char last_conclusion[65];
    float light_samples[5],frozen_light_baseline; uint64_t light_times[5]; unsigned light_count,light_next;
    uint64_t light_open_start,light_close_start,tilt_open_start,tilt_close_start;
    bool tilt_open_emitted,tilt_close_emitted;
    uint8_t pose_state,pending_pose; uint64_t pose_pending_since;
    bool calibrated,calibrating,calibration_started,pose_known; uint64_t calibration_start; unsigned calibration_count;
    double base_x,base_y,base_z,sum_x,sum_y,sum_z; float tilt;
    uint64_t impact_until,impact_start,impact_last; float impact_peak; unsigned impact_samples;
    bool sentry_arming,sentry_armed; uint32_t revision,duration_s;
    uint64_t arm_due,armed_until; bool fast_latched;
    bool fence_configured; double fence_lat,fence_lon,fence_radius;
    unsigned outside_count,inside_count; bool outside;
    SbGpsMode gps_mode;uint32_t gps_seed;
    SbSource source;
} Rules;
static Rules r;

static SbEventLevel event_level(SbEventType t) {
    if(t==SB_EVENT_SENTRY_INTRUSION)return SB_LEVEL_CRITICAL;
    if(t==SB_EVENT_ENV_HIGH||t==SB_EVENT_ENV_LOW||t==SB_EVENT_IMPACT||t==SB_EVENT_GEOFENCE_EXIT)return SB_LEVEL_WARNING;
    return SB_LEVEL_INFO;
}
static SbResult emit(SbEventType type,uint64_t now,const char *json) {
    SbEventCandidate e; memset(&e,0,sizeof(e));e.uptime_ms=now;e.source=r.source;
    e.valid=true;e.type=type;e.level=event_level(type);
    if(json)snprintf(e.data_json,sizeof(e.data_json),"%s",json);
    SbResult x=sb_event_buffer_put(&e);if(x==SB_OK)++r.events;return x;
}
static bool sample_valid(const SbSensorFrame *f,SbInputKind kind) {
    return f&&f->kind==kind&&f->role==sb_config_get()->role&&f->valid;
}
void sb_rules_init(void) {
    memset(&r,0,sizeof(r));r.source=SB_SOURCE_REAL;snprintf(r.profile_id,sizeof(r.profile_id),"demo");r.tl=2.0f;r.th=8.0f;r.hl=30.0f;r.hh=85.0f;
    r.t_hyst=1.0f;r.h_hyst=3.0f;r.box_door=SB_DOOR_UNKNOWN;
}
SbResult sb_rules_apply_profile(float tl,float th,float hl,float hh,float t_h,float h_h,const char *id) {
    size_t i,n;
    if(!isfinite(tl)||!isfinite(th)||!isfinite(hl)||!isfinite(hh)||!isfinite(t_h)||!isfinite(h_h)||
       tl>=th||hl>=hh||t_h<=0||h_h<=0||t_h>=th-tl||h_h>=hh-hl||!id)return SB_INVALID_ARG;
    n=strlen(id);if(!n||n>=sizeof(r.profile_id))return SB_INVALID_ARG;
    for(i=0;i<n;++i)if(!((id[i]>='A'&&id[i]<='Z')||(id[i]>='a'&&id[i]<='z')||(id[i]>='0'&&id[i]<='9')||id[i]=='-'||id[i]=='_'))return SB_INVALID_ARG;
    r.tl=tl;r.th=th;r.hl=hl;r.hh=hh;r.t_hyst=t_h;r.h_hyst=h_h;snprintf(r.profile_id,sizeof(r.profile_id),"%s",id);
    r.temp_in=r.temp_out=r.humidity_in=r.humidity_out=0;r.temp_pending=r.humidity_pending=false;return SB_OK;
}
SbResult sb_rules_start_calibration(void) {
    r.calibrated=false;r.calibrating=true;r.calibration_started=false;r.calibration_start=0;r.calibration_count=0;r.sum_x=r.sum_y=r.sum_z=0;return SB_OK;
}
SbResult sb_rules_set_gps_mode(SbGpsMode mode,uint32_t seed) {
    if(mode<SB_GPS_FIXED||mode>SB_GPS_WALK)return SB_INVALID_ARG;
    r.gps_mode=mode;r.gps_seed=seed;r.outside_count=r.inside_count=0;return sb_sensor_set_gps_mode(mode,seed);
}
static bool suppressed(uint64_t now){return r.box_door==SB_DOOR_OPEN&&now<r.suppress_until;}
static void env_metric(bool valid,float value,float low,float high,float hyst,int *state,unsigned *in,unsigned *out,
                       bool *pending,uint64_t now,const char *metric) {
    char data[160];bool high_bad,low_bad,recover;
    if(!valid){*in=*out=0;*pending=false;return;}
    high_bad=value>high;low_bad=value<low;
    if(*state==0){
        if(high_bad||low_bad){if(++*in>=3){int next=high_bad?1:-1;*state=next;*in=0;
            snprintf(data,sizeof(data),"{\"metric\":\"%s\",\"value\":%.3f,\"threshold\":%.3f,\"profile_id\":\"%s\"}",metric,(double)value,(double)(next>0?high:low),r.profile_id);
            if(suppressed(now))++r.suppressed;else (void)emit(next>0?SB_EVENT_ENV_HIGH:SB_EVENT_ENV_LOW,now,data);
        }}else *in=0;
        return;
    }
    recover=*state>0?value<=high-hyst:value>=low+hyst;
    if(recover){if(++*out>=3){float threshold=*state>0?high-hyst:low+hyst;*state=0;*out=0;*in=0;
        snprintf(data,sizeof(data),"{\"metric\":\"%s\",\"value\":%.3f,\"threshold\":%.3f,\"profile_id\":\"%s\"}",metric,(double)value,(double)threshold,r.profile_id);
        if(suppressed(now))++r.suppressed;else (void)emit(SB_EVENT_ENV_RECOVERED,now,data);
    }}else *out=0;
}
static float light_baseline(uint64_t now) {
    float sum=0;unsigned n=0,i;
    if(r.box_door==SB_DOOR_OPEN&&r.frozen_light_baseline>0)return r.frozen_light_baseline;
    for(i=0;i<r.light_count;++i)if(now>=r.light_times[i]&&now-r.light_times[i]<=5000){sum+=r.light_samples[i];++n;}
    if(!n)return 0.0f;
    sum/=(float)n;
    return sum>1.0f?sum:1.0f;
}
/* Hi3861 libm_flash exposes incomplete trigonometric entry points. */
static double cosine_approx(double radians) {
    double square=radians*radians;
    return 1.0-square*0.5+square*square*(1.0/24.0-square/720.0);
}
static SbResult alarm_lamp(uint64_t now) {
    SbOutputRequest q={sb_config_get()->role==SB_ENV?SB_LIGHT:SB_INDICATOR,true,10000};
    (void)now;return sb_output_set(&q);
}
static void fast_alert(uint64_t now,bool active,const char *reason) {
    char data[128];
    if(active&&!r.fast_latched&&r.sentry_armed){
        (void)alarm_lamp(now);snprintf(data,sizeof(data),"{\"reason\":\"%s\",\"sentry_revision\":%u}",reason,r.revision);(void)emit(SB_EVENT_SENTRY_INTRUSION,now,data);
        r.fast_latched=true;
    }
    if(!active)r.fast_latched=false;
}
static void env_process(const SbSensorFrame *f) {
    uint64_t now=f->clock.uptime_ms;SbOutputState light={0};float base,open_ratio;char data[160];bool fast=false;
    bool valid=sample_valid(f,SB_INPUT_ENV)&&isfinite(f->value.env.temperature_c)&&isfinite(f->value.env.humidity_pct)&&isfinite(f->value.env.light_lux);
    env_metric(valid,f->value.env.temperature_c,r.tl,r.th,r.t_hyst,&r.temp_state,&r.temp_in,&r.temp_out,&r.temp_pending,now,"temp_c");
    env_metric(valid,f->value.env.humidity_pct,r.hl,r.hh,r.h_hyst,&r.humidity_state,&r.humidity_in,&r.humidity_out,&r.humidity_pending,now,"humi_pct");
    if(!valid)return;
    (void)sb_output_get(SB_LIGHT,&light);if(!light.on&&now>=light.changed_ms+2000){
        base=light_baseline(now);
        if(base>0){open_ratio=f->value.env.light_lux/base;
            if(open_ratio>3.0f&&f->value.env.light_lux>50.0f){fast=true;if(!r.light_open_start)r.light_open_start=now;
                if(now-r.light_open_start>=1500&&r.box_door!=SB_DOOR_OPEN){snprintf(data,sizeof(data),"{\"action\":\"OPEN\",\"source\":\"LIGHT\",\"actuator_excluded\":true,\"lux\":%.2f}",(double)f->value.env.light_lux);(void)emit(SB_EVENT_DOOR_EVIDENCE,now,data);r.frozen_light_baseline=base;r.box_door=SB_DOOR_OPEN;r.suppress_until=now+30000;r.light_close_start=0;}
            }else r.light_open_start=0;
            if(f->value.env.light_lux<=base*1.5f){if(!r.light_close_start)r.light_close_start=now;
                if(now-r.light_close_start>=1500&&r.box_door==SB_DOOR_OPEN){snprintf(data,sizeof(data),"{\"action\":\"CLOSE\",\"source\":\"LIGHT\",\"actuator_excluded\":true,\"lux\":%.2f}",(double)f->value.env.light_lux);(void)emit(SB_EVENT_DOOR_EVIDENCE,now,data);r.box_door=SB_DOOR_CLOSED;r.frozen_light_baseline=0;r.light_close_start=0;}
            }else r.light_close_start=0;
        }
        if(r.box_door==SB_DOOR_CLOSED){r.light_samples[r.light_next]=f->value.env.light_lux;r.light_times[r.light_next]=now;r.light_next=(r.light_next+1)%5;if(r.light_count<5)++r.light_count;}
    }
    fast_alert(now,fast,"LIGHT");
}
static uint8_t classify(float tilt) {
    if(tilt<10)return 0;
    if(tilt<45)return 1;
    if(tilt<90)return 2;
    return 3;
}
static void finalize_impact(uint64_t now);
static const char *pose_name(uint8_t state){static const char *names[]={"NORMAL","TILTED","SIDEWAYS","INVERTED"};return state<4?names[state]:"UNKNOWN";}
static void pose_process(const SbSensorFrame *f) {
    double x,y,z,mag,dot,base_mag;float angle;uint64_t now;char data[160];bool impact,abnormal=false;
    if(!sample_valid(f,SB_INPUT_POSE))return;
    now=f->clock.uptime_ms;x=f->value.pose.x_g;y=f->value.pose.y_g;z=f->value.pose.z_g;
    if(!isfinite(x)||!isfinite(y)||!isfinite(z))return;
    mag=sqrt(x*x+y*y+z*z);if(!isfinite(mag)||mag<0.1)return;
    if(!r.calibrated){
        if(mag<0.9||mag>1.1){r.calibration_start=0;r.calibration_started=false;r.calibration_count=0;r.sum_x=r.sum_y=r.sum_z=0;return;}
        if(!r.calibrating)r.calibrating=true;
        if(!r.calibration_started){r.calibration_start=now;r.calibration_started=true;}
        r.sum_x+=x;r.sum_y+=y;r.sum_z+=z;++r.calibration_count;
        if(now-r.calibration_start>=3000&&r.calibration_count){r.base_x=r.sum_x/r.calibration_count;r.base_y=r.sum_y/r.calibration_count;r.base_z=r.sum_z/r.calibration_count;base_mag=sqrt(r.base_x*r.base_x+r.base_y*r.base_y+r.base_z*r.base_z);
            if(base_mag>0.1){r.base_x/=base_mag;r.base_y/=base_mag;r.base_z/=base_mag;r.calibrated=true;r.calibrating=false;r.calibration_started=false;}}
        if(!r.calibrated)return;
    }
    dot=(x*r.base_x+y*r.base_y+z*r.base_z)/mag;dot=dot>1?1:dot< -1?-1:dot;
    angle=(float)(acos(dot)*57.295779513);if(!isfinite(angle))return;r.tilt=angle;
    uint8_t next=classify(angle);
    if(!r.pose_known){r.pose_state=next;r.pending_pose=next;r.pose_known=true;r.pose_pending_since=0;}
    else if(next!=r.pose_state){
        /* Two degrees of hysteresis on each boundary, in either direction. */
        float threshold=r.pose_state==0?12.0f:r.pose_state==1?(next>r.pose_state?47.0f:8.0f):r.pose_state==2?(next>r.pose_state?92.0f:43.0f):88.0f;
        bool crossed=next>r.pose_state?angle>=threshold:angle<=threshold;
        if(!crossed){r.pending_pose=r.pose_state;r.pose_pending_since=0;}
        else if(r.pending_pose!=next){r.pending_pose=next;r.pose_pending_since=now;}
        else if(now-r.pose_pending_since>=200){uint8_t prev=r.pose_state;r.pose_state=next;r.pending_pose=next;
            snprintf(data,sizeof(data),"{\"previous_state\":\"%s\",\"state\":\"%s\",\"tilt_deg\":%.2f}",pose_name(prev),pose_name(next),(double)angle);(void)emit(SB_EVENT_POSE_CHANGED,now,data);}
    }else {r.pose_pending_since=0;r.pending_pose=r.pose_state;}
    impact=fabs(mag-1.0)>0.5;
    if(impact){
        if(r.impact_samples&&now-r.impact_last>1000)finalize_impact(now);
        if(!r.impact_samples){r.impact_peak=(float)mag;r.impact_samples=1;r.impact_start=now;r.impact_until=now+1000;}
        else{if(mag>r.impact_peak)r.impact_peak=(float)mag;++r.impact_samples;r.impact_until=now+1000;}
        r.impact_last=now;
    }
    if(r.pose_state!=0)abnormal=true;
    fast_alert(now,abnormal||impact,impact?"IMPACT":"POSE");
    if(angle>25){r.tilt_close_start=0;r.tilt_close_emitted=false;if(!r.tilt_open_start)r.tilt_open_start=now;
        if(now-r.tilt_open_start>=1500&&!r.tilt_open_emitted){snprintf(data,sizeof(data),"{\"action\":\"OPEN\",\"source\":\"TILT\",\"actuator_excluded\":true,\"tilt_deg\":%.2f}",(double)angle);(void)emit(SB_EVENT_DOOR_EVIDENCE,now,data);r.tilt_open_emitted=true;}}
    else {r.tilt_open_start=0;r.tilt_open_emitted=false;}
    if(angle<10){r.tilt_open_start=0;r.tilt_open_emitted=false;if(!r.tilt_close_start)r.tilt_close_start=now;
        if(now-r.tilt_close_start>=1500&&!r.tilt_close_emitted){snprintf(data,sizeof(data),"{\"action\":\"CLOSE\",\"source\":\"TILT\",\"actuator_excluded\":true,\"tilt_deg\":%.2f}",(double)angle);(void)emit(SB_EVENT_DOOR_EVIDENCE,now,data);r.tilt_close_emitted=true;}}
    else {r.tilt_close_start=0;r.tilt_close_emitted=false;}
}
static void finalize_impact(uint64_t now) {
    char data[128];if(!r.impact_samples||now<r.impact_until)return;
    snprintf(data,sizeof(data),"{\"peak_g\":%.3f,\"duration_ms\":%llu,\"quality\":\"VALID\"}",(double)r.impact_peak,(unsigned long long)(r.impact_last-r.impact_start));
    (void)emit(SB_EVENT_IMPACT,now,data);r.impact_samples=0;
}
SbResult sb_rules_process(const SbSensorFrame *f) {
    if(!f)return SB_INVALID_ARG;
    if(f->role!=sb_config_get()->role)return SB_UNSUPPORTED_CAP;
    r.source=f->source;
    if(f->kind==SB_INPUT_ENV){if(sb_config_get()->role!=SB_ENV)return SB_UNSUPPORTED_CAP;env_process(f);}
    else if(f->kind==SB_INPUT_POSE){if(sb_config_get()->role!=SB_POSE)return SB_UNSUPPORTED_CAP;pose_process(f);}
    else if(f->kind==SB_INPUT_LOCATION){
        uint64_t now;double lat,lon,dx,dy,distance;char data[128];
        if(sb_config_get()->role!=SB_POSE||!sample_valid(f,SB_INPUT_LOCATION))return SB_UNSUPPORTED_CAP;
        if(!r.fence_configured)return SB_OK;
        now=f->clock.uptime_ms;lat=f->value.location.latitude_deg;lon=f->value.location.longitude_deg;
        if(!isfinite(lat)||!isfinite(lon)||lat< -90||lat>90||lon< -180||lon>180)return SB_OK;
        dx=(lon-r.fence_lon)*111320.0*cosine_approx(r.fence_lat*0.017453292519943295);dy=(lat-r.fence_lat)*111320.0;distance=sqrt(dx*dx+dy*dy);
        if(distance>r.fence_radius){r.inside_count=0;if(r.outside_count<3)++r.outside_count;
            if(!r.outside&&r.outside_count>=3){r.outside=true;snprintf(data,sizeof(data),"{\"lat\":%.7f,\"lon\":%.7f,\"distance_m\":%.1f,\"src\":\"MOCK\"}",lat,lon,distance);(void)emit(SB_EVENT_GEOFENCE_EXIT,now,data);fast_alert(now,true,"POSE");}}
        else if(distance<r.fence_radius-10){r.outside_count=0;if(r.inside_count<3)++r.inside_count;
            if(r.outside&&r.inside_count>=3){r.outside=false;snprintf(data,sizeof(data),"{\"lat\":%.7f,\"lon\":%.7f,\"distance_m\":%.1f,\"src\":\"MOCK\"}",lat,lon,distance);(void)emit(SB_EVENT_GEOFENCE_ENTER,now,data);}}
        else r.outside_count=r.inside_count=0;
    }
    else return SB_INVALID_ARG;
    return SB_OK;
}
SbResult sb_rules_poll(uint64_t now) {
    finalize_impact(now);
    if(r.sentry_arming&&now>=r.arm_due){r.sentry_arming=false;r.sentry_armed=true;if(r.duration_s)r.armed_until=now+(uint64_t)r.duration_s*1000;}
    if(r.sentry_armed&&r.armed_until&&now>=r.armed_until){r.sentry_armed=false;r.armed_until=0;r.fast_latched=false;}
    return SB_OK;
}
SbResult sb_rules_set_sentry(uint32_t revision,bool arm,uint32_t duration) {
    SbClock now;if(sb_clock_get(&now)!=SB_OK)return SB_PLATFORM_ERROR;
    if(revision<=r.revision||duration>86400)return SB_INVALID_ARG;
    r.revision=revision;r.sentry_arming=false;r.sentry_armed=false;r.armed_until=0;r.fast_latched=false;r.duration_s=duration;
    if(arm){r.sentry_arming=true;r.arm_due=now.uptime_ms+3000;}
    return SB_OK;
}
SbResult sb_rules_box_state(SbDoorState door,bool historical,const char *id) {
    uint64_t now;if(!id||!*id||strlen(id)>=sizeof(r.last_conclusion)||historical)return SB_INVALID_ARG;
    if(!strcmp(id,r.last_conclusion))return SB_NO_DATA;
    SbClock t;if(sb_clock_get(&t)!=SB_OK)return SB_PLATFORM_ERROR;now=t.uptime_ms;
    snprintf(r.last_conclusion,sizeof(r.last_conclusion),"%s",id);r.box_door=door;
    if(door==SB_DOOR_OPEN)r.suppress_until=now+30000;else if(door==SB_DOOR_CLOSED)r.suppress_until=0;
    return SB_OK;
}
SbResult sb_rules_set_geofence(double lat,double lon,double radius) {
    if(!isfinite(lat)||!isfinite(lon)||lat< -90||lat>90||lon< -180||lon>180||!isfinite(radius)||radius<=10)return SB_INVALID_ARG;
    r.fence_lat=lat;r.fence_lon=lon;r.fence_radius=radius;r.fence_configured=true;r.outside_count=r.inside_count=0;return SB_OK;
}
SbResult sb_rules_status(SbRulesStatus *out) {
    if(!out)return SB_INVALID_ARG;
    out->calibrated=r.calibrated;out->tilt_deg=r.tilt;out->pose_state=r.pose_state;
    out->calibrating=r.calibrating;out->door=r.box_door;out->sentry_armed=r.sentry_armed;out->sentry_arming=r.sentry_arming;out->sentry_revision=r.revision;out->gps_mode=r.gps_mode;snprintf(out->profile_id,sizeof(out->profile_id),"%s",r.profile_id);out->event_candidates=r.events;out->suppressed_environment_events=r.suppressed;return SB_OK;
}
