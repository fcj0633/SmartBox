#include "sb_config.h"
#include "sb_hardware.h"
#include <stdio.h>
#include <string.h>
static uint32_t checksum(const char *s) { uint32_t c=2166136261u;while(*s) { c^=(uint8_t)*s++;c*=16777619u; }return c; }
static bool decode(const char *s,uint32_t *value) {
    char prefix[96],record[120]; unsigned long v,hash;char extra;
    if(sscanf(s,"%lu:%lx%c",&v,&hash,&extra)!=2||v>UINT32_MAX||hash>UINT32_MAX) return false;
    snprintf(prefix,sizeof(prefix),"%s:%lu",sb_config_get()->node_id,v);
    snprintf(record,sizeof(record),"%lu:%08lx",v,(unsigned long)checksum(prefix));
    if(strcmp(record,s)!=0) return false;
    *value=(uint32_t)v;return true;
}
SbResult sb_boot_reserve(uint32_t floor,uint32_t *out) {
    char records[2][120]={{0}},prefix[96],next[120],verify[120]; uint32_t values[2]={0},n;
    SbResult r[2]; unsigned i;
    if(!out||floor==UINT32_MAX) return SB_INVALID_ARG;
    for(i=0;i<2;++i) {
        r[i]=sb_boot_read(i,records[i],sizeof(records[i]));
        if(r[i]!=SB_OK && r[i]!=SB_NO_DATA) return SB_PLATFORM_ERROR;
        if(r[i]==SB_OK && !decode(records[i],&values[i])) return SB_PLATFORM_ERROR;
    }
    /* A missing partner after provisioning is not silently treated as a new board. */
    if((r[0]==SB_NO_DATA)!=(r[1]==SB_NO_DATA)) return SB_PLATFORM_ERROR;
    n=values[0]>values[1]?values[0]:values[1];if(n<floor)n=floor;
    if(n==UINT32_MAX) return SB_PLATFORM_ERROR;
    ++n;snprintf(prefix,sizeof(prefix),"%s:%lu",sb_config_get()->node_id,(unsigned long)n);
    snprintf(next,sizeof(next),"%lu:%08lx",(unsigned long)n,(unsigned long)checksum(prefix));
    /* Both exact records must be verified before this generation can connect. */
    for(i=0;i<2;++i) if(sb_boot_write(i,next)!=SB_OK || sb_boot_read(i,verify,sizeof(verify))!=SB_OK || strcmp(verify,next)) return SB_PLATFORM_ERROR;
    *out=n;return SB_OK;
}
