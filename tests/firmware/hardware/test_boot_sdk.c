#include "sb_hardware.h"
#include "hi_fs.h"
#include "hi_errno.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static char files[2][120];static unsigned error;static int mode,absent_stat_calls;
static int slot(const char *name) { return strcmp(name,"smartbox.boot.a")==0?0:1; }
int hi_get_fs_error(void) { return (int)error; }
int hi_open(const char *name,unsigned flags) {
    int i=slot(name);(void)flags;
    if(mode==1){error=HI_ERR_FS_IO_ERROR;return -1;}
    if(!files[i][0]){error=HI_ERR_FS_NOT_FOUND;return -1;}
    return i+1;
}
int hi_stat(const char *name,unsigned *size) {
    int i=slot(name);
    if(!files[i][0]){++absent_stat_calls;error=HI_ERR_FS_FILE_EXISTS;return -1;}
    *size=(unsigned)strlen(files[i]);return 0;
}
int hi_read(int fd,char *data,unsigned size) {
    memcpy(data,files[fd-1],size);return mode==3?(int)size-1:(int)size;
}
int hi_close(int fd) { (void)fd;return 0; }
int UtilsSetValue(const char *key,const char *value) {
    if(mode!=2)strcpy(files[slot(key)],value);
    return 0; /* Reproduce the SDK masking data-write failure with count success. */
}
int main(void) {
    uint32_t boot=0;char data[120];
    assert(sb_boot_read(0,data,sizeof(data))==SB_NO_DATA);
    assert(sb_boot_reserve(100,&boot)==SB_OK&&boot==101);
    assert(sb_boot_reserve(100,&boot)==SB_OK&&boot==102);
    assert(absent_stat_calls==0);
    mode=1;assert(sb_boot_reserve(100,&boot)==SB_PLATFORM_ERROR);
    mode=2;assert(sb_boot_reserve(100,&boot)==SB_PLATFORM_ERROR);
    mode=3;assert(sb_boot_reserve(100,&boot)==SB_PLATFORM_ERROR);
    mode=0;assert(sb_boot_reserve(100,&boot)==SB_OK&&boot==103);
    printf("PASS SDK boot read absence/error/write verification/restart\n");
    return 0;
}
