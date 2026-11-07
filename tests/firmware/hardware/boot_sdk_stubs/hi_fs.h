/* Test-only filesystem API: never included by production GN. */
#define HI_FS_O_RDONLY 0
int hi_stat(const char *name,unsigned *size);
int hi_get_fs_error(void);
int hi_open(const char *name,unsigned flags);
int hi_read(int fd,char *data,unsigned size);
int hi_close(int fd);
