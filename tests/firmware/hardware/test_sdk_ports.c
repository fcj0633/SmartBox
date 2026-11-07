/* Link the production SDK adapter with real SDK declarations and faulting APIs. */
#include "sb_config.h"
#include "sb_hardware.h"
#include "wifiiot_gpio.h"
#include "wifiiot_gpio_ex.h"
#include "wifiiot_i2c.h"
#include "cmsis_os2.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
static unsigned gpio_calls,i2c_calls;static int fail_gpio,fail_i2c;
static unsigned mux[15],dirs[15],values[15];
unsigned int GpioInit(void) { ++gpio_calls;if(fail_gpio){fail_gpio=0;return 1;}return gpio_calls>1?2:0; }
unsigned int IoSetFunc(WifiIotIoName pin,unsigned char function) { assert((unsigned)pin<15);mux[pin]=function;return 0; }
unsigned int GpioSetDir(WifiIotGpioIdx pin,WifiIotGpioDir dir) { dirs[pin]=dir;return 0; }
unsigned int GpioSetOutputVal(WifiIotGpioIdx pin,WifiIotGpioValue value) { values[pin]=value;return 0; }
unsigned int I2cInit(WifiIotI2cIdx id,unsigned int rate) { assert(id==WIFI_IOT_I2C_IDX_1&&rate==400000);++i2c_calls;if(fail_i2c){fail_i2c=0;return 1;}return 0; }
unsigned int I2cWrite(WifiIotI2cIdx id,unsigned short addr,const WifiIotI2cData *data) { (void)id;(void)addr;(void)data;return 0; }
unsigned int I2cRead(WifiIotI2cIdx id,unsigned short addr,const WifiIotI2cData *data) { (void)id;(void)addr;(void)data;return 0; }
unsigned int I2cWriteread(WifiIotI2cIdx id,unsigned short addr,const WifiIotI2cData *data) { return I2cRead(id,addr,data); }
uint32_t sb_platform_frequency(void) { return 1000; }
SbResult sb_ms_to_ticks(uint64_t ms,uint32_t hz,uint32_t *out) { (void)hz;*out=(uint32_t)ms;return SB_OK; }
osStatus_t osDelay(uint32_t ticks) { (void)ticks;return osOK; }
int main(int argc,char **argv) {
    int mode=argc>1?atoi(argv[1]):0;
    if(mode==1) { fail_gpio=1;assert(sb_hw_gpio_init()==SB_PLATFORM_ERROR);gpio_calls=0; }
    if(mode==2) { fail_i2c=1;assert(sb_hw_i2c_init()==SB_PLATFORM_ERROR); }
    assert(sb_hw_i2c_init()==SB_OK);
    assert(sb_hw_gpio_init()==SB_OK);
    assert(sb_hw_i2c_init()==SB_OK);
    assert(sb_hw_gpio_init()==SB_OK);
    assert(gpio_calls==1&&i2c_calls==(mode==2?2u:1u));
    unsigned pin=sb_config_get()->role==SB_ENV?14:7;
    assert(mux[pin]==(sb_config_get()->role==SB_ENV?WIFI_IOT_IO_FUNC_GPIO_14_GPIO:WIFI_IOT_IO_FUNC_GPIO_7_GPIO));
    assert(mux[8]==WIFI_IOT_IO_FUNC_GPIO_8_GPIO);
    assert(dirs[pin]==WIFI_IOT_GPIO_DIR_OUT&&values[pin]==WIFI_IOT_GPIO_VALUE0);
    assert(sb_hw_gpio(sb_config_get()->role==SB_ENV?SB_LIGHT:SB_INDICATOR,true)==SB_OK);
    assert(values[pin]==WIFI_IOT_GPIO_VALUE1);
    printf("PASS SDK port one-shot/retry/mux role=%d mode=%d\n",sb_config_get()->role,mode);
    return 0;
}
