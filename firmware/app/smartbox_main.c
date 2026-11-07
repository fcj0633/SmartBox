#include "sb_ports.h"
#include "sb_config.h"
#include "ohos_init.h"
#include <stdio.h>
void SmartBoxMain(void) {
    SbResult result=sb_app_start();
    SbAppStatus status;
    sb_app_status(&status);
    printf("SmartBox node=%s start=%d state=%d failure=%s reset_required=%d\n",
           sb_config_get()->node_id,(int)result,(int)status.state,
           status.failure?status.failure:"none",(int)status.reset_required);
}
APP_FEATURE_INIT(SmartBoxMain);
