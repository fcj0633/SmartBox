#include "sb_sntp.h"
#include <string.h>
static uint32_t be32(const uint8_t *p){return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];}
SbResult sb_sntp_decode(const uint8_t *p,size_t n,const uint8_t nonce[8],int64_t *utc,uint32_t *dispersion){
    uint64_t seconds,fraction,ms;unsigned version;
    if(!p||!nonce||!utc||!dispersion||n<48)return SB_INVALID_ARG;
    version=(p[0]>>3)&7;
    if((p[0]>>6)==3||(version!=3&&version!=4)||(p[0]&7)!=4||p[1]==0||p[1]>15||memcmp(p+24,nonce,8))return SB_INVALID_ARG;
    if((be32(p+32)==0&&be32(p+36)==0)||(be32(p+40)==0&&be32(p+44)==0))return SB_INVALID_ARG;
    seconds=be32(p+40);fraction=be32(p+44);
    /* Unfold the NTP 2036 rollover into the 1970..2106 Unix era. */
    if(seconds<2208988800ULL)seconds+=4294967296ULL;
    ms=(seconds-2208988800ULL)*1000+((fraction*1000)>>32);
    *utc=(int64_t)ms;*dispersion=(uint32_t)(((uint64_t)be32(p+8)*1000+65535)/65536);
    return SB_OK;
}

static uint64_t deadline(uint64_t now,uint64_t delay) { return now>UINT64_MAX-delay?UINT64_MAX:now+delay; }
bool sb_sntp_attempt_begin(SbSntpSchedule *schedule,uint64_t now) {
    if(!schedule || now<schedule->next_attempt_ms || now==UINT64_MAX)return false;
    schedule->next_attempt_ms=deadline(now,64000);
    return true;
}
void sb_sntp_attempt_success(SbSntpSchedule *schedule,uint64_t now) {
    if(schedule)schedule->next_attempt_ms=deadline(now,1800000);
}
