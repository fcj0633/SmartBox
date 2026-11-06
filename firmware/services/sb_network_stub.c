#include "sb_ports.h"
SbResult sb_network_poll(void) { return SB_NOT_IMPLEMENTED; }
SbResult sb_network_publish(const SbEventCandidate *item) { return item?SB_NOT_IMPLEMENTED:SB_INVALID_ARG; }
