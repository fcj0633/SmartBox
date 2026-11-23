#include "sb_rules.h"
#include "sb_rule_control.h"
#include "sb_config.h"
#include "sb_hardware.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static uint32_t tick;
int32_t sb_platform_lock(void){return 1;}
void sb_platform_unlock(int32_t prior){(void)prior;}
uint32_t sb_platform_tick(void){return tick;}
uint32_t sb_platform_frequency(void){return 1000;}
static const char *types[]={"NONE","ENV_HIGH","ENV_LOW","ENV_RECOVERED","POSE_CHANGED","IMPACT","DOOR_EVIDENCE","GEOFENCE_EXIT","GEOFENCE_ENTER","SENTRY_INTRUSION"};
static void start(void){tick=0;assert(sb_clock_init()==SB_OK);assert(sb_clock_sync(2000000,1)==SB_OK);assert(sb_sensor_init()==SB_OK);assert(sb_output_init()==SB_OK);sb_event_buffer_init();sb_rules_init();sb_control_init();}
static void mailbox(void){
    SbRuleCommand c={0};SbRuleResult out;SbRulesStatus snapshot;uint32_t tokens[8],token;unsigned i;
    c.kind=SB_CONTROL_SENTRY;c.on=true;c.duration=2;c.revision=1;c.expires_utc_ms=2015000;
    for(i=0;i<8;++i){c.revision=i+1;assert(sb_control_submit(&c,&tokens[i])==SB_OK);}
    assert(sb_control_submit(&c,&token)==SB_FULL&&sb_control_rejected()==1);
    assert(sb_control_status(&snapshot)==SB_OK&&!snapshot.sentry_arming&&snapshot.sentry_revision==0);
    assert(sb_control_take(tokens[0],&out)==SB_NO_DATA);assert(sb_control_process()==SB_OK);
    assert(sb_control_status(&snapshot)==SB_OK&&snapshot.sentry_arming&&snapshot.sentry_revision==8);
    tick=3000;assert(sb_control_process()==SB_OK);assert(sb_control_status(&snapshot)==SB_OK&&snapshot.sentry_armed);
    for(i=0;i<7;++i){assert(sb_control_take(tokens[i],&out)==SB_OK&&out.result!=SB_OK);}
    assert(sb_control_take(tokens[7],&out)==SB_OK&&out.result==SB_OK&&out.state.sentry_armed);
    snapshot.sentry_armed=false;assert(sb_control_status(&snapshot)==SB_OK&&snapshot.sentry_armed);
    tick=4999;assert(sb_control_process()==SB_OK);assert(sb_control_status(&snapshot)==SB_OK&&snapshot.sentry_armed);
    tick=5000;assert(sb_control_process()==SB_OK);assert(sb_control_status(&snapshot)==SB_OK&&!snapshot.sentry_armed);
    c.revision=9;c.expires_utc_ms=2005001;assert(sb_control_submit(&c,&token)==SB_OK);
    tick=5002;assert(sb_control_process()==SB_OK);assert(sb_control_take(token,&out)==SB_OK&&out.expired);
    assert(sb_control_status(&snapshot)==SB_OK&&snapshot.sentry_revision==8);
    c.kind=SB_CONTROL_BOX;c.door=SB_DOOR_OPEN;c.expires_utc_ms=0;strcpy(c.id,"mailbox-box");
    for(i=0;i<20;++i){assert(sb_control_submit(&c,&token)==SB_OK);assert(sb_control_process()==SB_OK);}
    assert(sb_control_status(&snapshot)==SB_OK&&snapshot.door==SB_DOOR_OPEN);
    /* A full volatile candidate buffer must not prevent sampling/rule execution. */
    SbEventCandidate e={0};for(i=0;i<16;++i)assert(sb_event_buffer_put(&e)==SB_OK);
    assert(sb_event_buffer_put(&e)==SB_FULL);assert(sb_rules_poll(6000)==SB_OK);
    puts("MAILBOX PASS");
}
int main(int argc,char **argv){
    char line[512],op[24];double a[8];unsigned long long ms;unsigned index=0;SbSensorFrame f;SbRulesStatus s;SbEventCandidate e;
    start();if(argc>1&&!strcmp(argv[1],"mailbox")){mailbox();return 0;}
    while(fgets(line,sizeof(line),stdin)){
        memset(a,0,sizeof(a));if(sscanf(line,"%23s %llu %lf %lf %lf %lf %lf %lf",op,&ms,&a[0],&a[1],&a[2],&a[3],&a[4],&a[5])<2)return 2;
        tick=(uint32_t)ms;assert(sb_rules_poll(ms)==SB_OK);assert(sb_output_poll()==SB_OK);
        memset(&f,0,sizeof(f));f.role=sb_config_get()->role;f.source=SB_SOURCE_REAL;f.clock.uptime_ms=ms;
        if(!strcmp(op,"env")){f.kind=SB_INPUT_ENV;f.valid=a[3]!=0;f.value.env.temperature_c=a[0];f.value.env.humidity_pct=a[1];f.value.env.light_lux=a[2];assert(sb_rules_process(&f)==SB_OK);}
        else if(!strcmp(op,"pose")){f.kind=SB_INPUT_POSE;f.valid=a[3]!=0;f.value.pose.x_g=a[0];f.value.pose.y_g=a[1];f.value.pose.z_g=a[2];assert(sb_rules_process(&f)==SB_OK);}
        else if(!strcmp(op,"gps")){f.kind=SB_INPUT_LOCATION;f.source=SB_SOURCE_MOCK;f.valid=a[3]!=0;f.sample_seq=(uint32_t)a[2];f.value.location.latitude_deg=a[0];f.value.location.longitude_deg=a[1];assert(sb_rules_process(&f)==SB_OK);}
        else if(!strcmp(op,"sentry")){(void)sb_rules_set_sentry((uint32_t)a[0],a[1]!=0,(uint32_t)a[2]);}
        else if(!strcmp(op,"box")){char id[24];snprintf(id,sizeof(id),"id-%u",(unsigned)a[1]);SbResult result=sb_rules_box_state(a[0]?SB_DOOR_OPEN:SB_DOOR_CLOSED,false,id);assert(result==SB_OK||result==SB_NO_DATA);}
        else if(!strcmp(op,"profile"))assert(sb_rules_apply_profile(a[0],a[1],a[2],a[3],a[4],a[5],"TEST")==SB_OK);
        else if(!strcmp(op,"fence"))assert(sb_rules_set_geofence(a[0],a[1],a[2])==SB_OK);
        else if(strcmp(op,"poll"))return 3;
        while(sb_event_buffer_get(&e)==SB_OK)printf("EVENT {\"step\":%u,\"type\":\"%s\",\"level\":%u,\"ms\":%llu,\"data\":%s}\n",index,types[e.type],e.level,(unsigned long long)e.uptime_ms,e.data_json);
        assert(sb_rules_status(&s)==SB_OK);
        printf("STATE {\"step\":%u,\"armed\":%s,\"arming\":%s,\"revision\":%lu,\"calibrated\":%s,\"pose\":%u}\n",index++,s.sentry_armed?"true":"false",s.sentry_arming?"true":"false",(unsigned long)s.sentry_revision,s.calibrated?"true":"false",s.pose_state);
    }
    return 0;
}
