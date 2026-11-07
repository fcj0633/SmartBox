#include "sb_config.h"
#include "sb_wire.h"
#include "cJSON.h"
#include <math.h>
#include <stdio.h>
#include <string.h>
static uint32_t boot,property_seq;
typedef struct {
    char request[257],operation[257],command[24],kind[16],result[12],code[32];
    uint64_t issued,expires;uint32_t caps,duration;bool on;
} Request;
static Request cache[32];static unsigned used,next;
static const char *role(void) {return sb_config_get()->role==SB_ENV?"ENV":"POSE";}
static cJSON *item(const cJSON *o,const char *k) {return cJSON_GetObjectItemCaseSensitive(o,k);}
static bool text(const cJSON *o,const char *k,const char *s) {cJSON *v=item(o,k);return cJSON_IsString(v)&&strcmp(v->valuestring,s)==0;}
static void number(cJSON *o,const char *k,uint64_t v) {
    char s[24];snprintf(s,sizeof(s),"%llu",(unsigned long long)v);cJSON_AddItemToObject(o,k,cJSON_CreateRaw(s));
}
static bool integer(const cJSON *o,const char *k,double lo,double hi,uint64_t *out) {
    cJSON *v=item(o,k);if(!cJSON_IsNumber(v)||!isfinite(v->valuedouble)||v->valuedouble<lo||v->valuedouble>hi||floor(v->valuedouble)!=v->valuedouble)return false;
    *out=(uint64_t)v->valuedouble;return true;
}
static bool string(const cJSON *o,const char *k,char *out,size_t cap) {
    cJSON *v=item(o,k);size_t i,n;
    if(!cJSON_IsString(v))return false;
    n=strlen(v->valuestring);if(!n||n>=cap)return false;
    for(i=0;i<n;++i)if((unsigned char)v->valuestring[i]<32)return false;
    memcpy(out,v->valuestring,n+1);return true;
}
/* Validate UTF-8 and reject NUL/control bytes before the SDK JSON parser. */
static bool utf8(const unsigned char *p,size_t n) {
    size_t i=0;while(i<n) {
        unsigned c=p[i++],count,min,cp,j;
        if(c<128){if(c==0||(c<32&&c!=9&&c!=10&&c!=13))return false;continue;}
        if(c>=0xc2&&c<=0xdf){count=1;min=0x80;cp=c&31;}
        else if(c>=0xe0&&c<=0xef){count=2;min=0x800;cp=c&15;}
        else if(c>=0xf0&&c<=0xf4){count=3;min=0x10000;cp=c&7;}else return false;
        if(i+count>n)return false;
        for(j=0;j<count;++j){c=p[i++];if((c&0xc0)!=0x80)return false;cp=(cp<<6)|(c&63);}
        if(cp<min||cp>0x10ffff||(cp>=0xd800&&cp<=0xdfff))return false;
    }return true;
}
static bool tree(const cJSON *p,unsigned depth) {
    const cJSON *a,*b;if(depth>12)return false;
    if(cJSON_IsNumber(p)&&!isfinite(p->valuedouble))return false;
    for(a=p->child;a;a=a->next) {
        if(cJSON_IsObject(p))for(b=a->next;b;b=b->next)if(a->string&&b->string&&!strcmp(a->string,b->string))return false;
        if(!tree(a,depth+1))return false;
    }return true;
}
static void spaces(const char **p,const char *end){while(*p<end&&(**p==' '||**p=='\t'||**p=='\n'||**p=='\r'))++*p;}
static bool json_string(const char **p,const char *end){
    if(*p==end||*(*p)++!='"')return false;
    while(*p<end){unsigned char c=(unsigned char)*(*p)++;unsigned i;
        if(c=='"')return true;
        if(c<32)return false;
        if(c=='\\'){
            if(*p==end)return false;
            c=(unsigned char)*(*p)++;
            if(c=='u'){for(i=0;i<4;++i){if(*p==end||!strchr("0123456789abcdefABCDEF",*(*p)++))return false;}}
            else if(!strchr("\"\\/bfnrt",c))return false;
        }
    }return false;
}
static bool json_value(const char **p,const char *end,unsigned depth){
    char close;unsigned i;
    spaces(p,end);if(*p==end||depth>12)return false;
    if(**p=='"')return json_string(p,end);
    if(**p=='{'||**p=='['){
        bool object=**p=='{';close=object?'}':']';++*p;spaces(p,end);
        if(*p<end&&**p==close){++*p;return true;}
        while(*p<end){
            if(object){if(!json_string(p,end))return false;spaces(p,end);if(*p==end||*(*p)++!=':')return false;}
            if(!json_value(p,end,depth+1))return false;
            spaces(p,end);
            if(*p==end)return false;
            if(**p==close){++*p;return true;}
            if(*(*p)++!=',')return false;
            spaces(p,end);
        }return false;
    }
    const char *words[]={"true","false","null"};
    for(i=0;i<3;++i){size_t n=strlen(words[i]);if((size_t)(end-*p)>=n&&!memcmp(*p,words[i],n)){*p+=n;return true;}}
    if(**p=='-'){++*p;if(*p==end)return false;}
    if(**p=='0')++*p;
    else {if(**p<'1'||**p>'9')return false;while(*p<end&&**p>='0'&&**p<='9')++*p;}
    if(*p<end&&**p=='.'){++*p;if(*p==end||**p<'0'||**p>'9')return false;while(*p<end&&**p>='0'&&**p<='9')++*p;}
    if(*p<end&&(**p=='e'||**p=='E')){++*p;if(*p<end&&(**p=='+'||**p=='-'))++*p;if(*p==end||**p<'0'||**p>'9')return false;while(*p<end&&**p>='0'&&**p<='9')++*p;}
    return true;
}
static cJSON *parse(const char *raw,size_t n) {
    char *copy;cJSON *o;unsigned depth=0;bool quoted=false,escape=false;size_t i;
    if(!raw||!n||n>SB_PAYLOAD_MAX||!utf8((const unsigned char*)raw,n))return NULL;
    /* Bound depth before cJSON recursion, and reject escapes that decode NUL. */
    for(i=0;i<n;++i) {
        char c=raw[i];
        if(quoted){if(escape){escape=false;if(c=='u'&&i+4<n&&!memcmp(raw+i+1,"0000",4))return NULL;}
            else if(c=='\\')escape=true;else if(c=='"')quoted=false;else if((unsigned char)c<32)return NULL;}
        else if(c=='"')quoted=true;
        else if(c=='{'||c=='['){if(++depth>12)return NULL;}
        else if(c=='}'||c==']'){if(!depth)return NULL;--depth;}
    }
    if(quoted||depth)return NULL;
    const char *cursor=raw;
    if(!json_value(&cursor,raw+n,0))return NULL;
    spaces(&cursor,raw+n);if(cursor!=raw+n)return NULL;
    /* Keep the 1.5KB copy off the CMSIS thread stack. SDK strtod/cJSON
     * have substantial nested frames on RV32. Allocation failure must not
     * execute a command or report success. cJSON owns copied tree strings. */
    copy=cJSON_malloc(n+1);if(!copy)return NULL;
    memcpy(copy,raw,n);copy[n]=0;o=cJSON_ParseWithOpts(copy,NULL,1);
    cJSON_free(copy);
    if(!cJSON_IsObject(o)||!tree(o,0)){cJSON_Delete(o);return NULL;}return o;
}
static void time_fields(cJSON *o,const SbClock *t) {
    if(t->utc_valid){number(o,"ts",(uint64_t)t->utc_ms);number(o,"time_uncertainty_ms",t->time_uncertainty_ms);}
    else {cJSON_AddNullToObject(o,"ts");cJSON_AddNullToObject(o,"time_uncertainty_ms");}
    cJSON_AddStringToObject(o,"ts_src",t->utc_valid?"SNTP":"UNSYNCED");number(o,"uptime_ms",t->uptime_ms);
}
static cJSON *envelope(const SbClock *t) {
    cJSON *o=cJSON_CreateObject();if(!o)return NULL;
    cJSON_AddNumberToObject(o,"v",1);cJSON_AddStringToObject(o,"box_id",sb_config_get()->box_id);
    cJSON_AddStringToObject(o,"node_id",sb_config_get()->node_id);number(o,"boot_id",boot);time_fields(o,t);return o;
}
static SbResult finish(cJSON *o,char *out,size_t cap) {
    SbResult rc=SB_FULL;
    if(!out||cap<2){cJSON_Delete(o);return SB_INVALID_ARG;}
    out[0]=0;
    if(o && cJSON_PrintPreallocated(o,out,(int)cap,0) && strlen(out)<=SB_PAYLOAD_MAX)rc=SB_OK;
    if(rc!=SB_OK)out[0]=0;
    cJSON_Delete(o);return rc;
}
void sb_wire_init(uint32_t id){boot=id;property_seq=0;used=next=0;memset(cache,0,sizeof(cache));}
uint32_t sb_wire_boot(void){return boot;}
SbResult sb_wire_property(char *out,size_t cap) {
    SbClock now;SbSnapshot s;SbOutputState light={0},fan={0};cJSON *o,*v;SbSensorFrame *f;
    if(property_seq==UINT32_MAX)return SB_FULL;
    if(sb_clock_get(&now)!=SB_OK||sb_pipeline_snapshot(&s)!=SB_OK)return SB_PLATFORM_ERROR;
    bool is_env=sb_config_get()->role==SB_ENV;
    SbSensorFrame *observed=&s.frames[is_env?SB_INPUT_ENV:SB_INPUT_POSE];
    o=envelope(s.present[is_env?SB_INPUT_ENV:SB_INPUT_POSE]?&observed->clock:&now);if(!o)return SB_PLATFORM_ERROR;
    number(o,"property_seq",++property_seq);cJSON_AddStringToObject(o,"role",role());number(o,"caps",sb_config_get()->capabilities);
    bool env=sb_config_get()->role==SB_ENV;f=&s.frames[env?SB_INPUT_ENV:SB_INPUT_POSE];
    bool valid=s.present[env?SB_INPUT_ENV:SB_INPUT_POSE]&&f->valid;
    v=cJSON_AddObjectToObject(o,env?"env":"pose");cJSON_AddBoolToObject(v,"valid",valid);
    if(env) {
        if(valid) {cJSON_AddNumberToObject(v,"temp_c",f->value.env.temperature_c);cJSON_AddNumberToObject(v,"humi_pct",f->value.env.humidity_pct);cJSON_AddNumberToObject(v,"lux",f->value.env.light_lux);}
        else {cJSON_AddNullToObject(v,"temp_c");cJSON_AddNullToObject(v,"humi_pct");cJSON_AddNullToObject(v,"lux");}
        (void)sb_output_get(SB_LIGHT,&light);(void)sb_output_get(SB_FAN,&fan);
        cJSON_AddBoolToObject(v,"light_on",light.on);cJSON_AddBoolToObject(v,"fan_on",fan.on);
    } else {
        if(valid) {cJSON *a=cJSON_AddArrayToObject(v,"acc_g");cJSON_AddItemToArray(a,cJSON_CreateNumber(f->value.pose.x_g));cJSON_AddItemToArray(a,cJSON_CreateNumber(f->value.pose.y_g));cJSON_AddItemToArray(a,cJSON_CreateNumber(f->value.pose.z_g));cJSON_AddNumberToObject(v,"tilt_deg",f->value.pose.tilt_deg);}
        else {cJSON_AddNullToObject(v,"acc_g");cJSON_AddNullToObject(v,"tilt_deg");}
        cJSON_AddStringToObject(v,"state","UNKNOWN");
        f=&s.frames[SB_INPUT_LOCATION];bool loc=s.present[SB_INPUT_LOCATION]&&f->valid;
        v=cJSON_AddObjectToObject(o,"loc");cJSON_AddStringToObject(v,"src","MOCK");cJSON_AddBoolToObject(v,"valid",loc);
        if(loc){cJSON_AddNumberToObject(v,"lat",f->value.location.latitude_deg);cJSON_AddNumberToObject(v,"lon",f->value.location.longitude_deg);
            cJSON *obs=cJSON_AddObjectToObject(v,"observation");number(obs,"sample_seq",f->sample_seq);time_fields(obs,&f->clock);}
        else{cJSON_AddNullToObject(v,"lat");cJSON_AddNullToObject(v,"lon");}
    }
    cJSON_AddStringToObject(o,"sample_source","REAL");
    return finish(o,out,cap);
}
SbResult sb_wire_heartbeat(char *out,size_t cap) {
    SbClock t;SbSnapshot s;cJSON *o,*v;bool valid;
    if(sb_clock_get(&t)!=SB_OK||sb_pipeline_snapshot(&s)!=SB_OK)return SB_PLATFORM_ERROR;
    o=envelope(&t);if(!o)return SB_PLATFORM_ERROR;
    cJSON_AddStringToObject(o,"role",role());number(o,"caps",sb_config_get()->capabilities);
    v=cJSON_AddObjectToObject(o,"sentry");cJSON_AddStringToObject(v,"state","UNKNOWN");number(v,"revision",0);
    unsigned k=sb_config_get()->role==SB_ENV?SB_INPUT_ENV:SB_INPUT_POSE;
    valid=s.present[k]&&s.frames[k].valid;
    cJSON_AddStringToObject(o,"sensor_quality",valid?(k==SB_INPUT_ENV?"VALID":"UNCALIBRATED"):"INVALID");
    number(o,"pending_events",0);number(o,"overflow_total",sb_event_buffer_rejected());
    cJSON_AddArrayToObject(o,"lost_ranges");cJSON_AddStringToObject(o,"storage","DEGRADED");
    return finish(o,out,cap);
}
static bool decode(cJSON *o,Request *r) {
    uint64_t n;cJSON *p=item(o,"params"),*on;
    memset(r,0,sizeof(*r));
    if(!integer(o,"v",1,1,&n)||!text(o,"box_id",sb_config_get()->box_id)||!text(o,"node_id",sb_config_get()->node_id)||
       !string(o,"request_id",r->request,sizeof(r->request))||!string(o,"operation_id",r->operation,sizeof(r->operation))||
       !string(o,"cmd",r->command,sizeof(r->command))||!integer(o,"target_caps",1,127,&n)||!cJSON_IsObject(p))return false;
    r->caps=(uint32_t)n;
    unsigned chars=0;const unsigned char *s=(const unsigned char*)r->request;
    for(;*s;++s)if((*s&0xc0)!=0x80)++chars;
    if(chars>64)return false;
    chars=0;s=(const unsigned char*)r->operation;
    for(;*s;++s)if((*s&0xc0)!=0x80)++chars;
    if(chars>64)return false;
    if(!integer(o,"issued_at",0,9007199254740991.0,&r->issued)||!integer(o,"expires_at",0,9007199254740991.0,&r->expires))return false;
    if(!strcmp(r->command,"output")) {
        if(!string(p,"kind",r->kind,sizeof(r->kind))||!integer(p,"duration_s",1,30,&n)||!cJSON_IsBool(on=item(p,"on")))return false;
        r->duration=(uint32_t)n;r->on=cJSON_IsTrue(on);
        if(strcmp(r->kind,"light")&&strcmp(r->kind,"fan")&&strcmp(r->kind,"indicator"))return false;
    }
    return true;
}
static bool same(const Request *a,const Request *b) {
    return !strcmp(a->operation,b->operation)&&!strcmp(a->command,b->command)&&!strcmp(a->kind,b->kind)&&a->issued==b->issued&&a->expires==b->expires&&a->caps==b->caps&&a->duration==b->duration&&a->on==b->on;
}
SbResult sb_wire_command(const char *payload,size_t length,bool retained,char *reply,size_t cap) {
    Request r;SbClock now;cJSON *o=parse(payload,length),*ack,*state;unsigned i;bool cached=false;
    if(!o||!decode(o,&r)){cJSON_Delete(o);return SB_INVALID_ARG;}
    if(sb_clock_get(&now)!=SB_OK){cJSON_Delete(o);return SB_PLATFORM_ERROR;}
    strcpy(r.result,"error");strcpy(r.code,"invalid_params");
    if(retained)strcpy(r.code,"retained_command");
    else if(r.expires<=r.issued||r.expires-r.issued>15000) { /* reject invalid lifetime */ }
    else {
        for(i=0;i<used;++i)if(!strcmp(cache[i].request,r.request)) {
            if(same(&cache[i],&r))r=cache[i];else strcpy(r.code,"conflicting_request");cached=true;break;
        }
        if(!cached) {
            if((r.caps&sb_config_get()->capabilities)!=r.caps)strcpy(r.code,"unsupported_caps");
            else if(!now.utc_valid)strcpy(r.code,"time_unsynced");
            else if((uint64_t)now.utc_ms>=r.expires){strcpy(r.result,"expired");strcpy(r.code,"expired");}
            /* issued_at is creation metadata, not a not-before/scheduled-action
             * field in the frozen contract. Keep lifetime/order and expiry
             * validation; a zero-skew lower bound rejects valid SNTP clocks. */
            else if(strcmp(r.command,"output"))strcpy(r.code,"unsupported_command");
            else {
                SbOutputKind kind=!strcmp(r.kind,"light")?SB_LIGHT:!strcmp(r.kind,"fan")?SB_FAN:SB_INDICATOR;
                unsigned required=kind==SB_INDICATOR?16:kind==SB_LIGHT?2:4;
                if(r.caps!=required)strcpy(r.code,"unsupported_caps");
                else {
                    SbOutputRequest request={kind,r.on,r.duration*1000};SbResult rc=sb_output_set(&request);
                    if(rc==SB_OK){strcpy(r.result,"ok");strcpy(r.code,"completed");}
                    else strcpy(r.code,rc==SB_UNSUPPORTED_CAP?"unsupported_caps":"storage_error");
                }
            }
            cache[next]=r;next=(next+1)%32;if(used<32)++used;
        }
    }
    ack=envelope(&now);cJSON_Delete(o);if(!ack)return SB_PLATFORM_ERROR;
    cJSON_AddStringToObject(ack,"request_id",r.request);cJSON_AddStringToObject(ack,"operation_id",r.operation);
    cJSON_AddStringToObject(ack,"result",r.result);cJSON_AddStringToObject(ack,"code",r.code);state=cJSON_AddObjectToObject(ack,"actual_state");
    if(!strcmp(r.code,"completed")){cJSON_AddStringToObject(state,"kind",r.kind);cJSON_AddBoolToObject(state,"on",r.on);}
    return finish(ack,reply,cap);
}
SbResult sb_wire_box_state(const char *payload,size_t length) {
    cJSON *o=parse(payload,length);uint64_t v;char id[65];bool valid=o&&integer(o,"v",1,1,&v)&&text(o,"box_id",sb_config_get()->box_id)&&text(o,"node_id",sb_config_get()->node_id)&&
        (text(o,"door_state","OPEN")||text(o,"door_state","CLOSED")||text(o,"door_state","UNKNOWN"))&&
        string(o,"conclusion_id",id,sizeof(id))&&cJSON_IsFalse(item(o,"historical"))&&integer(o,"ts",0,9007199254740991.0,&v);
    /* No suppression TTL or business action is inferred from this message. */
    cJSON_Delete(o);return valid?SB_OK:SB_INVALID_ARG;
}
