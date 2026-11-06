#include "sb_ports.h"
#include "ohos_init.h"
#include <stdio.h>
void SmartBoxMain(void) {
    SbResult result=sb_app_start();
    printf("SmartBox skeleton start=%d; MOCK inputs, network disabled\n",(int)result);
}
APP_FEATURE_INIT(SmartBoxMain);
