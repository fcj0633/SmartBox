#include "sb_wifi.h"
#include "hi_wifi_api.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static hi_wifi_auth_mode auth;static int failure,missing;static unsigned count=32;
int hi_wifi_sta_scan_results(hi_wifi_ap_info *list,unsigned *n) {
    assert(*n==32);if(failure)return -1;
    memset(list,0,32*sizeof(*list));*n=count;
    if(!missing&&count<=32&&count){strcpy(list[count-1].ssid,"target");list[count-1].auth=auth;}
    return 0;
}
int main(void) {
    SbWifiAuth result;
    auth=HI_WIFI_SECURITY_WPA2PSK;assert(sb_wifi_find("target",&result)==SB_OK&&result==SB_WIFI_PSK);
    auth=HI_WIFI_SECURITY_WPAPSK_WPA2PSK_MIX;assert(sb_wifi_find("target",&result)==SB_OK&&result==SB_WIFI_PSK);
    auth=HI_WIFI_SECURITY_SAE;assert(sb_wifi_find("target",&result)==SB_OK&&result==SB_WIFI_SAE);
    auth=HI_WIFI_SECURITY_OPEN;assert(sb_wifi_find("target",&result)==SB_UNSUPPORTED_CAP);
    auth=HI_WIFI_SECURITY_WEP;assert(sb_wifi_find("target",&result)==SB_UNSUPPORTED_CAP);
    missing=1;assert(sb_wifi_find("target",&result)==SB_NO_DATA);missing=0;
    count=0;assert(sb_wifi_find("target",&result)==SB_NO_DATA);
    count=33;assert(sb_wifi_find("target",&result)==SB_PLATFORM_ERROR);count=32;
    failure=1;assert(sb_wifi_find("target",&result)==SB_PLATFORM_ERROR);
    assert(sb_wifi_find(NULL,&result)==SB_INVALID_ARG);
    assert(sb_wifi_find("target",NULL)==SB_INVALID_ARG);
    puts("PASS SDK Wi-Fi scan 32 entries/auth mappings/rejections/errors");return 0;
}
