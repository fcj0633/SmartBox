#ifndef TEST_CMSIS_H
#define TEST_CMSIS_H
#include <stdint.h>
typedef enum { osOK=0, osError=-1,osErrorTimeout=-2,osErrorResource=-3 } osStatus_t;
typedef enum { osPriorityBelowNormal=16,osPriorityNormal=24,osPriorityAboveNormal=32 } osPriority_t;
typedef void (*osThreadFunc_t)(void *);
typedef void *osThreadId_t;
typedef void *osMessageQueueId_t;
typedef struct { const char *name;uint32_t attr_bits;void *cb_mem;uint32_t cb_size;
    void *stack_mem;uint32_t stack_size;osPriority_t priority;uint32_t tz_module;uint32_t reserved; } osThreadAttr_t;
int32_t osKernelLock(void);
int32_t osKernelUnlock(void);
uint32_t osKernelGetTickCount(void);
uint32_t osKernelGetTickFreq(void);
osStatus_t osDelay(uint32_t ticks);
osThreadId_t osThreadNew(osThreadFunc_t func,void *arg,const osThreadAttr_t *attr);
osMessageQueueId_t osMessageQueueNew(uint32_t count,uint32_t size,const void *attr);
osStatus_t osMessageQueuePut(osMessageQueueId_t q,const void *item,uint8_t priority,uint32_t timeout);
osStatus_t osMessageQueueGet(osMessageQueueId_t q,void *out,uint8_t *priority,uint32_t timeout);
osStatus_t osMessageQueueDelete(osMessageQueueId_t q);
#endif
