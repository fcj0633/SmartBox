#include "sb_config.h"
#include "sb_hardware.h"
#include "wifiiot_gpio.h"
#include "wifiiot_gpio_ex.h"
#include "wifiiot_i2c.h"
#include "wifiiot_i2c_ex.h"
#include "cmsis_os2.h"
/* SDK hi_gpio_init is one-shot; both sensor and output setup share it.
 * Called during startup and sensor retries by the sampling thread only. */
static bool gpio_initialized, i2c_initialized;
static SbResult gpio_init_once(void) {
    if (gpio_initialized) return SB_OK;
    if (GpioInit()!=0) return SB_PLATFORM_ERROR;
    gpio_initialized=true;
    return SB_OK;
}
void sb_hw_delay(uint32_t ms) { uint32_t ticks; if(sb_ms_to_ticks(ms,sb_platform_frequency(),&ticks)==SB_OK && ticks) (void)osDelay(ticks); }
SbResult sb_hw_i2c_init(void) {
    if(i2c_initialized) return SB_OK;
    if(gpio_init_once()!=SB_OK || IoSetFunc(WIFI_IOT_IO_NAME_GPIO_0,WIFI_IOT_IO_FUNC_GPIO_0_I2C1_SDA)!=0 ||
       IoSetFunc(WIFI_IOT_IO_NAME_GPIO_1,WIFI_IOT_IO_FUNC_GPIO_1_I2C1_SCL)!=0 ||
       I2cInit(WIFI_IOT_I2C_IDX_1,400000)!=0) return SB_PLATFORM_ERROR;
    i2c_initialized=true;
    return SB_OK;
}
SbResult sb_hw_write(uint8_t address,const uint8_t *p,size_t n) {
    WifiIotI2cData data={0}; data.sendBuf=(unsigned char *)p;data.sendLen=n;
    return I2cWrite(WIFI_IOT_I2C_IDX_1,address<<1,&data)==0?SB_OK:SB_PLATFORM_ERROR;
}
SbResult sb_hw_read(uint8_t address,const uint8_t *prefix,size_t pn,uint8_t *p,size_t n) {
    WifiIotI2cData data={0}; data.sendBuf=(unsigned char *)prefix;data.sendLen=pn;data.receiveBuf=p;data.receiveLen=n;
    unsigned rc=pn?I2cWriteread(WIFI_IOT_I2C_IDX_1,address<<1,&data):I2cRead(WIFI_IOT_I2C_IDX_1,(address<<1)|1,&data);
    return rc==0?SB_OK:SB_PLATFORM_ERROR;
}
SbResult sb_hw_gpio(SbOutputKind kind,bool on) {
    WifiIotGpioIdx pin=kind==SB_LIGHT?WIFI_IOT_IO_NAME_GPIO_14:kind==SB_FAN?WIFI_IOT_IO_NAME_GPIO_8:WIFI_IOT_IO_NAME_GPIO_7;
    return GpioSetOutputVal(pin,on?WIFI_IOT_GPIO_VALUE1:WIFI_IOT_GPIO_VALUE0)==0?SB_OK:SB_PLATFORM_ERROR;
}
SbResult sb_hw_gpio_init(void) {
    unsigned i;
    if(gpio_init_once()!=SB_OK) return SB_PLATFORM_ERROR;
    const unsigned pins_env[]={14,8},pins_pose[]={7,8};
    const unsigned *pins=sb_config_get()->role==SB_ENV?pins_env:pins_pose;
    for(i=0;i<2;++i) if(IoSetFunc((WifiIotIoName)pins[i],
        pins[i]==14?WIFI_IOT_IO_FUNC_GPIO_14_GPIO:
        pins[i]==8?WIFI_IOT_IO_FUNC_GPIO_8_GPIO:WIFI_IOT_IO_FUNC_GPIO_7_GPIO)!=0 ||
        GpioSetDir((WifiIotGpioIdx)pins[i],WIFI_IOT_GPIO_DIR_OUT)!=0 ||
        GpioSetOutputVal((WifiIotGpioIdx)pins[i],WIFI_IOT_GPIO_VALUE0)!=0) return SB_PLATFORM_ERROR;
    return SB_OK;
}
