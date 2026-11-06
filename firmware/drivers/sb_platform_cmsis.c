#include "sb_ports.h"
#include "cmsis_os2.h"
int32_t sb_platform_lock(void) { return osKernelLock(); }
void sb_platform_unlock(int32_t prior) {
    /* This SDK does not acquire an extra lock when already locked. */
    if (prior==0) (void)osKernelUnlock();
}
uint32_t sb_platform_tick(void) { return osKernelGetTickCount(); }
uint32_t sb_platform_frequency(void) { return osKernelGetTickFreq(); }
