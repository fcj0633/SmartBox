#ifndef SB_RULE_CONTROL_H
#define SB_RULE_CONTROL_H
#include "sb_rules.h"
/* Network submits values; only processing executes rule mutations. Eight slots
 * remain occupied until their result is consumed. No wait or heap allocation. */
typedef enum { SB_CONTROL_PROFILE, SB_CONTROL_GPS, SB_CONTROL_CALIBRATE,
    SB_CONTROL_SENTRY, SB_CONTROL_BOX } SbControlKind;
typedef struct {
    SbControlKind kind; uint64_t expires_utc_ms; bool on; uint32_t revision,duration,seed;
    float tl,th,hl,hh,t_hyst,h_hyst; SbGpsMode mode; SbDoorState door;
    char id[65];
} SbRuleCommand;
typedef struct { uint32_t token; SbResult result; bool expired; SbRulesStatus state; } SbRuleResult;
void sb_control_init(void);
SbResult sb_control_submit(const SbRuleCommand *command,uint32_t *token);
SbResult sb_control_process(void); /* processing thread only */
SbResult sb_control_take(uint32_t token,SbRuleResult *out);
SbResult sb_control_status(SbRulesStatus *out);
uint64_t sb_control_rejected(void);
#endif
