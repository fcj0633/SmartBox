#ifndef SB_WIRE_H
#define SB_WIRE_H
#include "sb_ports.h"
#define SB_PAYLOAD_MAX 1536
/* Network thread owns wire state and the 32-entry execution result cache. */
void sb_wire_init(uint32_t boot_id);
SbResult sb_wire_property(char *out,size_t capacity);
SbResult sb_wire_heartbeat(char *out,size_t capacity);
SbResult sb_wire_command(const char *payload,size_t length,bool retained,char *reply,size_t capacity);
SbResult sb_wire_box_state(const char *payload,size_t length);
uint32_t sb_wire_boot(void);
#endif
