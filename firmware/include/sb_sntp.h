#ifndef SB_SNTP_H
#define SB_SNTP_H
#include "sb_types.h"
typedef struct { uint64_t next_attempt_ms; } SbSntpSchedule;
/* Failed attempts remain eligible after 64s, independent of MQTT reconnects. */
bool sb_sntp_attempt_begin(SbSntpSchedule *schedule,uint64_t now_ms);
void sb_sntp_attempt_success(SbSntpSchedule *schedule,uint64_t now_ms);
/* RFC 4330 unicast response checks; socket adapter also checks source endpoint. */
SbResult sb_sntp_decode(const uint8_t *packet,size_t length,const uint8_t nonce[8],
                        int64_t *utc_ms,uint32_t *dispersion_ms);
#endif
