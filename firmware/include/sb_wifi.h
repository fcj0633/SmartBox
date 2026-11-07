#ifndef SB_WIFI_H
#define SB_WIFI_H
#include "sb_types.h"
typedef enum { SB_WIFI_PSK, SB_WIFI_SAE } SbWifiAuth;
/* Network-thread only. Never downgrades an unsupported/open AP to PSK. */
SbResult sb_wifi_find(const char *ssid,SbWifiAuth *auth);
#endif
