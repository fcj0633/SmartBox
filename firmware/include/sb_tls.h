#ifndef SB_TLS_H
#define SB_TLS_H
#include <stdbool.h>
/* Single network-thread owner; socket remains owned by network transport. */
int sb_tls_open(int socket_fd, const char *hostname, const char *ca_pem);
int sb_tls_transfer(unsigned char *data, int length, int timeout_ms, bool writing);
void sb_tls_close(void);
#endif
