#include "sb_wifi.h"
#include "hi_wifi_api.h"
#include <string.h>
#include <stdio.h>
/* Driver limit is 32; OpenHarmony GetScanInfoList rejects a smaller caller
 * buffer rather than truncating, and fails to map plain WPA2PSK. */
static hi_wifi_ap_info scans[32];
SbResult sb_wifi_find(const char *ssid,SbWifiAuth *auth) {
    unsigned count=32,index;bool found=false;
    if(!ssid||!auth||!ssid[0]||strlen(ssid)>HI_WIFI_MAX_SSID_LEN)return SB_INVALID_ARG;
    memset(scans,0,sizeof(scans));
    if(hi_wifi_sta_scan_results(scans,&count)!=0 || count>32)return SB_PLATFORM_ERROR;
    printf("SB wifi scan count=%u\n",count);
    for(index=0;index<count;++index) {
        if(memchr(scans[index].ssid,0,sizeof(scans[index].ssid))==NULL || strcmp(scans[index].ssid,ssid))continue;
        found=true;
        printf("SB wifi target auth=%d\n",(int)scans[index].auth);
        switch(scans[index].auth) {
            case HI_WIFI_SECURITY_WPA2PSK:
            case HI_WIFI_SECURITY_WPAPSK_WPA2PSK_MIX:*auth=SB_WIFI_PSK;return SB_OK;
            case HI_WIFI_SECURITY_SAE:*auth=SB_WIFI_SAE;return SB_OK;
            default:break;
        }
    }
    return found?SB_UNSUPPORTED_CAP:SB_NO_DATA;
}
