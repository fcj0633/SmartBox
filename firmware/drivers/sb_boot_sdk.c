#include "sb_hardware.h"
#include "kv_store.h"
#include "hi_fs.h"
#include "hi_errno.h"
#include <string.h>
#include <stdio.h>
static const char *keys[]={"smartbox.boot.a","smartbox.boot.b"};
SbResult sb_boot_read(unsigned slot,char *data,size_t cap) {
    unsigned size;int fd,n,closed;
    if(slot>1||!data||cap<2)return SB_INVALID_ARG;
    memset(data,0,cap);
    /* This SDK's linked hi_stat maps an absent filename to FILE_EXISTS.
     * Open first: hi_open maps true ENOENT to NOT_FOUND. Never reinterpret
     * an arbitrary stat/open failure as permission to reset boot identity. */
    fd=hi_open(keys[slot],HI_FS_O_RDONLY);
    if(fd<0) {
        unsigned error=(unsigned)hi_get_fs_error();
        if(error==HI_ERR_FS_NOT_FOUND)return SB_NO_DATA;
        printf("SB boot slot=%u open error=0x%08x\n",slot,error);
        return SB_PLATFORM_ERROR;
    }
    if(hi_stat(keys[slot],&size)<0 || !size || size>=cap) {
        (void)hi_close(fd);return SB_PLATFORM_ERROR;
    }
    n=hi_read(fd,data,size);closed=hi_close(fd);
    if(n!=(int)size||closed<0||memchr(data,0,size))return SB_PLATFORM_ERROR;
    data[size]=0;return SB_OK;
}
SbResult sb_boot_write(unsigned slot,const char *data) {
    if(slot>1||!data)return SB_INVALID_ARG;
    /* Caller verifies actual file bytes, bypassing KV's optional RAM cache.
     * SDK UtilsSetValue can return its item-count write result after data failure. */
    return UtilsSetValue(keys[slot],data)==0?SB_OK:SB_PLATFORM_ERROR;
}
