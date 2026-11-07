#include "sb_config.h"
#include "sb_hardware.h"
#include "sb_wire.h"
#include "sb_sntp.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <limits.h>
#include <stdlib.h>
#include "cJSON.h"
static bool allocation_fail;static unsigned allocations,frees;
static void *checked_malloc(size_t size){if(allocation_fail){allocation_fail=false;return NULL;}void *p=malloc(size);if(p)++allocations;return p;}
static void checked_free(void *p){if(p)++frees;free(p);}
static uint32_t tick=1000;static int io_error,gpio_error,writes,corrupt_crc;
static bool outputs[3];static char slots[2][120];static int write_fail,read_fail;
static unsigned tests;
#define CHECK(x) do { assert(x);++tests; } while(0)
int32_t sb_platform_lock(void){return 0;}
void sb_platform_unlock(int32_t prior){(void)prior;}
uint32_t sb_platform_tick(void){return tick;}
uint32_t sb_platform_frequency(void){return 1000;}
void sb_hw_delay(uint32_t ms){tick+=ms;}
SbResult sb_hw_i2c_init(void){return io_error?SB_PLATFORM_ERROR:SB_OK;}
SbResult sb_hw_write(uint8_t address,const uint8_t *p,size_t n){(void)address;(void)p;(void)n;return io_error?SB_PLATFORM_ERROR:SB_OK;}
static uint8_t crc(const uint8_t *p){unsigned i,j;uint8_t c=255;for(i=0;i<2;++i){c^=p[i];for(j=0;j<8;++j)c=c&128?(uint8_t)((c<<1)^0x31):(uint8_t)(c<<1);}return c;}
SbResult sb_hw_read(uint8_t address,const uint8_t *prefix,size_t pn,uint8_t *p,size_t n){
    if(io_error)return SB_PLATFORM_ERROR;
    if(address==0x23){assert(n==2&&pn==0);p[0]=0;p[1]=120;}
    else if(address==0x44){assert(n==6&&pn==2);p[0]=0x66;p[1]=0x66;p[2]=crc(p);p[3]=0x80;p[4]=0;p[5]=crc(p+3);if(corrupt_crc)++p[2];}
    else {assert(address==0x68&&pn==1);if(*prefix==0x75){assert(n==1);*p=0x68;}else{assert(n==6);memset(p,0,n);p[4]=8;}}
    return SB_OK;
}
SbResult sb_hw_gpio_init(void){memset(outputs,0,sizeof(outputs));return gpio_error?SB_PLATFORM_ERROR:SB_OK;}
SbResult sb_hw_gpio(SbOutputKind k,bool on){if(gpio_error)return SB_PLATFORM_ERROR;outputs[k]=on;++writes;return SB_OK;}
SbResult sb_boot_read(unsigned slot,char *p,size_t n){if(read_fail)return SB_PLATFORM_ERROR;if(!*slots[slot])return SB_NO_DATA;if(strlen(slots[slot])>=n)return SB_PLATFORM_ERROR;strcpy(p,slots[slot]);return SB_OK;}
SbResult sb_boot_write(unsigned slot,const char *p){if(write_fail)return SB_PLATFORM_ERROR;strcpy(slots[slot],p);return SB_OK;}
static void command(char *out,const char *id,unsigned caps,const char *kind,bool on,unsigned duration){
    snprintf(out,1537,"{\"v\":1,\"box_id\":\"BOX-0001\",\"node_id\":\"%s\",\"request_id\":\"%s\",\"operation_id\":\"op-test\",\"cmd\":\"output\",\"target_caps\":%u,\"issued_at\":2000000,\"expires_at\":2015000,\"params\":{\"kind\":\"%s\",\"on\":%s,\"duration_s\":%u}}",sb_config_get()->node_id,id,caps,kind,on?"true":"false",duration);
}
int main(void){
    SbSensorFrame frame;SbOutputState state;SbClock clock;uint32_t boot;char raw[1537],reply[1537];int n;
    bool env=sb_config_get()->role==SB_ENV;SbInputKind input=env?SB_INPUT_ENV:SB_INPUT_POSE;
    SbOutputKind output=env?SB_LIGHT:SB_INDICATOR;const char *kind=env?"light":"indicator";unsigned caps=env?2:16;
    CHECK(sb_clock_init()==SB_OK);CHECK(sb_clock_get(&clock)==SB_OK&&!clock.utc_valid);
    CHECK(sb_sensor_init()==SB_OK);CHECK(sb_sensor_read(input,&frame)==SB_OK&&frame.valid&&frame.source==SB_SOURCE_REAL);
    if(env){CHECK(frame.value.env.light_lux>99&&frame.value.env.light_lux<101);corrupt_crc=1;CHECK(sb_sensor_read(input,&frame)==SB_OK&&!frame.valid);corrupt_crc=0;}
    else {CHECK(frame.value.pose.z_g==1&&frame.value.pose.tilt_deg==0);CHECK(sb_sensor_read(SB_INPUT_LOCATION,&frame)==SB_OK&&frame.source==SB_SOURCE_MOCK&&frame.sample_seq==1);}
    io_error=1;CHECK(sb_sensor_read(input,&frame)==SB_OK&&!frame.valid);io_error=0;
    CHECK(sb_sensor_read(input,&frame)==SB_OK&&frame.valid);CHECK(sb_sensor_read(env?SB_INPUT_POSE:SB_INPUT_ENV,&frame)==SB_UNSUPPORTED_CAP);
    CHECK(sb_output_init()==SB_OK);CHECK(sb_output_get(output,&state)==SB_OK&&!state.on);
    SbOutputRequest request={output,true,2000};CHECK(sb_output_set(&request)==SB_OK&&outputs[output]);
    tick+=2000;CHECK(sb_output_poll()==SB_OK&&!outputs[output]);
    request.kind=env?SB_INDICATOR:SB_FAN;CHECK(sb_output_set(&request)==SB_UNSUPPORTED_CAP);
    request.kind=output;gpio_error=1;CHECK(sb_output_set(&request)==SB_PLATFORM_ERROR);gpio_error=0;
    CHECK(sb_boot_reserve(10,&boot)==SB_OK&&boot==11);CHECK(sb_boot_reserve(10,&boot)==SB_OK&&boot==12);
    write_fail=1;CHECK(sb_boot_reserve(10,&boot)==SB_PLATFORM_ERROR);write_fail=0;read_fail=1;CHECK(sb_boot_reserve(10,&boot)==SB_PLATFORM_ERROR);read_fail=0;
    slots[0][0]='x';CHECK(sb_boot_reserve(10,&boot)==SB_PLATFORM_ERROR);memset(slots,0,sizeof(slots));
    CHECK(sb_boot_reserve(UINT32_MAX,&boot)==SB_INVALID_ARG);CHECK(sb_boot_reserve(UINT32_MAX-1,&boot)==SB_OK&&boot==UINT32_MAX);CHECK(sb_boot_reserve(0,&boot)==SB_PLATFORM_ERROR);
    sb_pipeline_init();CHECK(sb_sensor_read(input,&frame)==SB_OK&&sb_pipeline_process(&frame)==SB_OK);
    if(!env){CHECK(sb_sensor_read(SB_INPUT_LOCATION,&frame)==SB_OK&&sb_pipeline_process(&frame)==SB_OK);}
    sb_wire_init(20);CHECK(sb_wire_property(raw,sizeof(raw))==SB_OK);printf("FIXTURE property %s\n",raw);
    CHECK(sb_wire_heartbeat(raw,sizeof(raw))==SB_OK);printf("FIXTURE heartbeat %s\n",raw);
    command(raw,"unsynced",caps,kind,true,2);CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&strstr(reply,"time_unsynced"));
    CHECK(sb_clock_sync(2000000,250)==SB_OK);CHECK(sb_clock_get(&clock)==SB_OK&&clock.utc_valid&&clock.utc_ms==2000000);
    command(raw,"valid",caps,kind,true,2);n=writes;
    CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&strstr(reply,"completed")&&writes==n+1);printf("FIXTURE command_ack %s\n",reply);
    tick+=500;CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&writes==n+1);
    command(raw,"valid",caps,kind,false,2);CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&strstr(reply,"conflicting_request")&&writes==n+1);
    command(raw,"retained",caps,kind,true,2);CHECK(sb_wire_command(raw,strlen(raw),true,reply,sizeof(reply))==SB_OK&&strstr(reply,"retained_command"));
    command(raw,"badcaps",127,kind,true,2);CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&strstr(reply,"unsupported_caps"));
    command(raw,"badparams",caps,kind,true,31);CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_INVALID_ARG);
    command(raw,"expired",caps,kind,true,2);tick+=16000;CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&strstr(reply,"expired"));
    CHECK(sb_wire_command("{\"v\":1,\"v\":1}",13,false,reply,sizeof(reply))==SB_INVALID_ARG);
    CHECK(sb_wire_command("{\"v\":NaN}",9,false,reply,sizeof(reply))==SB_INVALID_ARG);
    CHECK(sb_wire_command("{} garbage",10,false,reply,sizeof(reply))==SB_INVALID_ARG);
    memset(raw,'[',1537);CHECK(sb_wire_command(raw,1537,false,reply,sizeof(reply))==SB_INVALID_ARG);
    CHECK(sb_wire_box_state("{}",2)==SB_INVALID_ARG);
    const char *bad[]={"{\"v\":01}","{\"v\":1,}","{\"v\":+1}","{\"v\":.1}","{\"v\":1.}","{\"v\":1e}","{\"v\":1e999}","{\"v\":1,\"x\":\"\\u0000\"}","{\"v\":1,\"x\":\"\\q\"}","{\"v\":1,\"x\":\"\xc0\x80\"}"};
    unsigned i;
    for(i=0;i<sizeof(bad)/sizeof(bad[0]);++i)CHECK(sb_wire_command(bad[i],strlen(bad[i]),false,reply,sizeof(reply))==SB_INVALID_ARG);
    /* Fully valid envelopes ensure syntax rejection, rather than a missing field,
     * proves malformed/duplicate unknown fields cannot reach execution. */
    const char *tails[]={",\"unknown\":01}",",\"unknown\":+1}",",}",",\"v\":1}",",\"unknown\":[1,]}"};
    for(i=0;i<sizeof(tails)/sizeof(tails[0]);++i){
        command(raw,"syntax",caps,kind,true,2);raw[strlen(raw)-1]=0;strcat(raw,tails[i]);
        CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_INVALID_ARG);
    }
    char unicode_id[257];for(i=0;i<64;++i)memcpy(unicode_id+i*3,"\xe4\xb8\xad",3);unicode_id[192]=0;
    command(raw,unicode_id,caps,kind,true,2);
    CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&strstr(reply,"expired"));
    memcpy(unicode_id+192,"\xe4\xb8\xad",3);unicode_id[195]=0;command(raw,unicode_id,caps,kind,true,2);
    CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_INVALID_ARG);
    /* Timestamp source/era and unsolicited/Kiss-o'-Death response rejection. */
    uint8_t packet[48]={0},nonce[8]={1,2,3,4,5,6,7,8};int64_t utc;uint32_t dispersion;
    packet[0]=0x24;packet[1]=2;memcpy(packet+24,nonce,8);packet[32]=1;
    packet[40]=0xe1;packet[41]=0xb6;packet[42]=0x5f;packet[43]=0x80;
    CHECK(sb_sntp_decode(packet,48,nonce,&utc,&dispersion)==SB_OK&&utc>1500000000000LL);
    packet[0]=0xe4;CHECK(sb_sntp_decode(packet,48,nonce,&utc,&dispersion)==SB_INVALID_ARG);packet[0]=0x24;
    packet[1]=0;CHECK(sb_sntp_decode(packet,48,nonce,&utc,&dispersion)==SB_INVALID_ARG);packet[1]=2;
    packet[24]=0;CHECK(sb_sntp_decode(packet,48,nonce,&utc,&dispersion)==SB_INVALID_ARG);packet[24]=1;
    CHECK(sb_sntp_decode(packet,47,nonce,&utc,&dispersion)==SB_INVALID_ARG);
    memset(packet+40,0,8);packet[43]=1;CHECK(sb_sntp_decode(packet,48,nonce,&utc,&dispersion)==SB_OK&&utc>2085978496000LL);
    CHECK(sb_wire_property(raw,20)==SB_FULL&&raw[0]==0);
    /* A live MQTT connection must not turn failed SNTP into a 30-minute wait. */
    SbSntpSchedule schedule={0};
    CHECK(sb_sntp_attempt_begin(&schedule,1000));
    CHECK(!sb_sntp_attempt_begin(&schedule,64999));
    CHECK(sb_sntp_attempt_begin(&schedule,65000));
    CHECK(!sb_sntp_attempt_begin(&schedule,128999));
    CHECK(sb_sntp_attempt_begin(&schedule,129000));
    sb_sntp_attempt_success(&schedule,129100);
    CHECK(!sb_sntp_attempt_begin(&schedule,1929099));
    CHECK(sb_sntp_attempt_begin(&schedule,1929100));
    CHECK(!sb_sntp_attempt_begin(NULL,0));
    schedule.next_attempt_ms=0;CHECK(sb_sntp_attempt_begin(&schedule,UINT64_MAX-10));
    CHECK(schedule.next_attempt_ms==UINT64_MAX&&!sb_sntp_attempt_begin(&schedule,UINT64_MAX));
    /* Scratch OOM and repeated parsing cannot change output or leak ownership. */
    cJSON_Hooks hooks={checked_malloc,checked_free};cJSON_InitHooks(&hooks);
    command(raw,"scratch-oom",caps,kind,false,1);
    allocation_fail=true;int before_writes=writes;
    CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_INVALID_ARG&&writes==before_writes);
    for(i=0;i<100;++i)CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&strstr(reply,"expired"));
    CHECK(allocations==frees);cJSON_InitHooks(NULL);
    /* Creation timestamps must not invent a zero-skew not-before restriction. */
    CHECK(sb_clock_sync(1999737,1165)==SB_OK);
    command(raw,"creation-metadata-off",caps,kind,false,10);n=writes;
    CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&strstr(reply,"completed")&&writes==n+1);
    tick+=500;CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&writes==n+1);
    command(raw,"overlong-life",caps,kind,false,10);memcpy(strstr(raw,"2015000"),"2015001",7);
    CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&strstr(reply,"invalid_params")&&writes==n+1);
    command(raw,"reversed-time",caps,kind,false,10);memcpy(strstr(raw,"2015000"),"1999999",7);
    CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&strstr(reply,"invalid_params")&&writes==n+1);
    CHECK(sb_clock_sync(2015000,1165)==SB_OK);command(raw,"at-expiry",caps,kind,false,10);
    CHECK(sb_wire_command(raw,strlen(raw),false,reply,sizeof(reply))==SB_OK&&strstr(reply,"expired")&&writes==n+1);
    printf("PASS hardware role=%s checks=%u\n",env?"ENV":"POSE",tests);return 0;
}
