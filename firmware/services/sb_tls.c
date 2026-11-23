#include "sb_tls.h"
#include "sb_ports.h"
#include "hi_cipher.h"
#include "lwip/sockets.h"
#include "mbedtls/ssl.h"
#include "mbedtls/x509_crt.h"
#include <string.h>
#include <time.h>

static mbedtls_ssl_context tls;
static mbedtls_ssl_config conf;
static mbedtls_x509_crt ca;
static int fd=-1;
static bool allocated;

static int random_bytes(void *unused,unsigned char *out,size_t n) {
    (void)unused;
    return hi_cipher_trng_get_random_bytes(out,n)==0?0:MBEDTLS_ERR_SSL_INTERNAL_ERROR;
}
static int bio_send(void *ctx,const unsigned char *buf,size_t n) {
    int rc=send(*(int*)ctx,buf,n,0);
    if(rc<0&&(errno==EAGAIN||errno==EWOULDBLOCK))return MBEDTLS_ERR_SSL_WANT_WRITE;
    return rc>0?rc:MBEDTLS_ERR_SSL_INTERNAL_ERROR;
}
static int bio_recv(void *ctx,unsigned char *buf,size_t n) {
    int rc=recv(*(int*)ctx,buf,n,0);
    if(rc<0&&(errno==EAGAIN||errno==EWOULDBLOCK))return MBEDTLS_ERR_SSL_WANT_READ;
    return rc;
}
/* This SDK's prebuilt mbedTLS has HAVE_TIME_DATE disabled. Check every
 * certificate's validity explicitly, while preserving all library flags. */
static int time_compare(const struct tm *a,const mbedtls_x509_time *b) {
    int av[]={a->tm_year+1900,a->tm_mon+1,a->tm_mday,a->tm_hour,a->tm_min,a->tm_sec};
    int bv[]={b->year,b->mon,b->day,b->hour,b->min,b->sec};unsigned i;
    for(i=0;i<6;++i)if(av[i]!=bv[i])return av[i]<bv[i]?-1:1;
    return 0;
}
static int verify_time(void *ctx,mbedtls_x509_crt *crt,int depth,uint32_t *flags) {
    SbClock now;struct tm utc;time_t seconds;
    (void)ctx;(void)depth;
    if(sb_clock_get(&now)!=SB_OK||!now.utc_valid){*flags|=MBEDTLS_X509_BADCERT_OTHER;return 0;}
    seconds=(time_t)(now.utc_ms/1000);
    if(!gmtime_r(&seconds,&utc)){*flags|=MBEDTLS_X509_BADCERT_OTHER;return 0;}
    if(time_compare(&utc,&crt->valid_from)<0)*flags|=MBEDTLS_X509_BADCERT_FUTURE;
    if(time_compare(&utc,&crt->valid_to)>0)*flags|=MBEDTLS_X509_BADCERT_EXPIRED;
    return 0;
}
static int wait_io(int want,uint64_t deadline) {
    SbClock now;fd_set readset,writeset;struct timeval tv;uint64_t left;
    if(sb_clock_get(&now)!=SB_OK||now.uptime_ms>=deadline)return -1;
    left=deadline-now.uptime_ms;tv.tv_sec=left/1000;tv.tv_usec=(left%1000)*1000;
    FD_ZERO(&readset);FD_ZERO(&writeset);
    if(want==MBEDTLS_ERR_SSL_WANT_WRITE)FD_SET(fd,&writeset);else FD_SET(fd,&readset);
    return select(fd+1,&readset,&writeset,NULL,&tv)>0?0:-1;
}
void sb_tls_close(void) {
    if(allocated){mbedtls_ssl_free(&tls);mbedtls_ssl_config_free(&conf);mbedtls_x509_crt_free(&ca);}
    allocated=false;fd=-1;
}
int sb_tls_open(int socket_fd,const char *hostname,const char *ca_pem) {
    SbClock now;int rc;uint64_t deadline;
    sb_tls_close();
    if(!hostname||!ca_pem||sb_clock_get(&now)!=SB_OK||!now.utc_valid)return -1;
    fd=socket_fd;mbedtls_ssl_init(&tls);mbedtls_ssl_config_init(&conf);mbedtls_x509_crt_init(&ca);allocated=true;
    if(mbedtls_x509_crt_parse(&ca,(const unsigned char*)ca_pem,strlen(ca_pem)+1)!=0)goto fail;
    if(mbedtls_ssl_config_defaults(&conf,MBEDTLS_SSL_IS_CLIENT,MBEDTLS_SSL_TRANSPORT_STREAM,MBEDTLS_SSL_PRESET_DEFAULT)!=0)goto fail;
    mbedtls_ssl_conf_authmode(&conf,MBEDTLS_SSL_VERIFY_REQUIRED);
    mbedtls_ssl_conf_min_version(&conf,MBEDTLS_SSL_MAJOR_VERSION_3,MBEDTLS_SSL_MINOR_VERSION_3);
    mbedtls_ssl_conf_ca_chain(&conf,&ca,NULL);mbedtls_ssl_conf_rng(&conf,random_bytes,NULL);
    mbedtls_ssl_conf_verify(&conf,verify_time,NULL);
    if(mbedtls_ssl_setup(&tls,&conf)!=0||mbedtls_ssl_set_hostname(&tls,hostname)!=0)goto fail;
    mbedtls_ssl_set_bio(&tls,&fd,bio_send,bio_recv,NULL);
    deadline=now.uptime_ms+12000;
    while((rc=mbedtls_ssl_handshake(&tls))!=0) {
        if((rc!=MBEDTLS_ERR_SSL_WANT_READ&&rc!=MBEDTLS_ERR_SSL_WANT_WRITE)||wait_io(rc,deadline)!=0)goto fail;
    }
    if(mbedtls_ssl_get_verify_result(&tls)!=0)goto fail;
    return 0;
fail:sb_tls_close();return -1;
}
int sb_tls_transfer(unsigned char *data,int length,int timeout_ms,bool writing) {
    SbClock now;uint64_t deadline;int total=0,rc;
    if(!allocated||length<=0||timeout_ms<0||sb_clock_get(&now)!=SB_OK)return -1;
    deadline=now.uptime_ms+(uint64_t)timeout_ms;
    while(total<length) {
        rc=writing?mbedtls_ssl_write(&tls,data+total,length-total):mbedtls_ssl_read(&tls,data+total,length-total);
        if(rc>0){total+=rc;continue;}
        if(rc!=MBEDTLS_ERR_SSL_WANT_READ&&rc!=MBEDTLS_ERR_SSL_WANT_WRITE)return -1;
        if(wait_io(rc,deadline)!=0)return total==0?0:-1;
    }
    return total;
}
