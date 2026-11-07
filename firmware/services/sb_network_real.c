#include "sb_config.h"
#include "sb_hardware.h"
#include "sb_wire.h"
#include "sb_sntp.h"
#include "sb_wifi.h"
#include "cmsis_os2.h"
#include "sb_local_config.h"
#include "MQTTClient.h"
#include "wifi_device.h"
#include "wifi_error_code.h"
#include "lwip/netifapi.h"
#include "lwip/dhcp.h"
#include "hi_cipher.h"
#include "lwip/sockets.h"
#include <stdio.h>
#include <string.h>
static MQTTClient client;static Network network;
static unsigned char send_buffer[2048],read_buffer[2048];
static char topic[3][129],up[129],payload[SB_PAYLOAD_MAX+1],reply[SB_PAYLOAD_MAX+1];
typedef struct { char payload[SB_PAYLOAD_MAX+1];size_t len;bool retain;unsigned type; } Incoming;
static Incoming received[4];static unsigned head,count;static uint64_t dropped;
static bool initialized,configured,connected,dhcp_started,transport,wifi_enabled,scanning;
static uint64_t scan_due;
static int wifi_id=-1;static uint64_t next_try,next_property,next_heartbeat,wifi_retry;
static uint64_t next_diagnostic;
static SbSntpSchedule sntp_schedule;
static unsigned backoff=1;static uint32_t boot;
static int transfer(Network *n,unsigned char *data,int length,int timeout,bool write_mode) {
    SbClock start,now;int total=0;
    if(length<=0||timeout<0||sb_clock_get(&start)!=SB_OK)return -1;
    while(total<length) {
        fd_set set;struct timeval tv;int rc;uint64_t left;
        if(sb_clock_get(&now)!=SB_OK)return -1;
        if(now.uptime_ms-start.uptime_ms>=(uint64_t)timeout)return total==0?0:-1;
        left=(uint64_t)timeout-(now.uptime_ms-start.uptime_ms);tv.tv_sec=left/1000;tv.tv_usec=(left%1000)*1000;
        FD_ZERO(&set);FD_SET(n->my_socket,&set);
        rc=select(n->my_socket+1,write_mode?NULL:&set,write_mode?&set:NULL,NULL,&tv);
        if(rc<=0)return total==0?0:-1;
        rc=write_mode?send(n->my_socket,data+total,length-total,0):recv(n->my_socket,data+total,length-total,0);
        if(rc<=0)return -1;
        total+=rc;
    }
    return total;
}
static int read_socket(Network *n,unsigned char *p,int len,int timeout){return transfer(n,p,len,timeout,false);}
static int write_socket(Network *n,unsigned char *p,int len,int timeout){return transfer(n,p,len,timeout,true);}
static void received_message(MessageData *data) {
    unsigned i;MQTTMessage *m=data->message;MQTTString *t=data->topicName;
    if(m->payloadlen>SB_PAYLOAD_MAX||count==4){++dropped;return;}
    for(i=0;i<3;++i)if(strlen(topic[i])==(size_t)t->lenstring.len && !memcmp(topic[i],t->lenstring.data,t->lenstring.len))break;
    if(i==3){++dropped;return;}
    Incoming *p=&received[(head+count)%4];p->len=m->payloadlen;p->retain=m->retained;p->type=i;
    memcpy(p->payload,m->payload,p->len);p->payload[p->len]=0;++count;
}
static SbResult publish(const char *suffix,const char *raw,int qos) {
    MQTTMessage m={0};size_t n=strlen(raw);
    if(!connected||n>SB_PAYLOAD_MAX)return SB_PLATFORM_ERROR;
    snprintf(up,sizeof(up),"box/%s/%s/%s",sb_config_get()->box_id,sb_config_get()->node_id,suffix);
    m.qos=(enum QoS)qos;m.payload=(void *)raw;m.payloadlen=n;m.retained=0;
    return MQTTPublish(&client,up,&m)==SUCCESS?SB_OK:SB_PLATFORM_ERROR;
}
/* Explicit socket deadlines avoid the SDK sample's unbounded connect(). */
static int connect_socket(void) {
    struct sockaddr_in addr={0};struct timeval wait={3,0};int fd,flags,rc,error=0;socklen_t size=sizeof(error);fd_set writes;
    addr.sin_family=AF_INET;addr.sin_port=htons(1883);
    if(inet_pton(AF_INET,SB_BROKER_IPV4,&addr.sin_addr)!=1)return -1;
    fd=socket(AF_INET,SOCK_STREAM,0);if(fd<0)return -1;
    flags=fcntl(fd,F_GETFL,0);if(flags<0||fcntl(fd,F_SETFL,flags|O_NONBLOCK)<0){lwip_close(fd);return -1;}
    rc=connect(fd,(struct sockaddr*)&addr,sizeof(addr));
    if(rc<0&&errno==EINPROGRESS){FD_ZERO(&writes);FD_SET(fd,&writes);rc=select(fd+1,NULL,&writes,NULL,&wait);
        if(rc>0 && getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&size)==0 && !error)rc=0;else rc=-1;}
    if(rc<0){lwip_close(fd);return -1;}
    network.my_socket=fd;transport=true;return 0;
}
static void disconnect(void) {
    connected=false;client.isconnected=0;
    if(transport){NetworkDisconnect(&network);transport=false;}
    head=count=0;
}
static bool wifi_ready(uint64_t now) {
    WifiLinkedInfo link={0};struct netif *iface;
    if(!configured) {
        WifiDeviceConfig c={0};
        if(!wifi_enabled){if(EnableWifi()!=WIFI_SUCCESS)return false;wifi_enabled=true;}
        if(!scanning){if(Scan()!=WIFI_SUCCESS)return false;scan_due=now+3000;scanning=true;return false;}
        if(now<scan_due)return false;
        SbWifiAuth auth;SbResult scan_result=sb_wifi_find(SB_WIFI_SSID,&auth);
        if(scan_result!=SB_OK){printf("SB wifi target lookup result=%d\n",(int)scan_result);scanning=false;return false;}
        if(strlen(SB_WIFI_SSID)>=sizeof(c.ssid)||strlen(SB_WIFI_PASSWORD)>=sizeof(c.preSharedKey))return false;
        memcpy(c.ssid,SB_WIFI_SSID,strlen(SB_WIFI_SSID)+1);memcpy(c.preSharedKey,SB_WIFI_PASSWORD,strlen(SB_WIFI_PASSWORD)+1);
        c.securityType=auth==SB_WIFI_PSK?WIFI_SEC_TYPE_PSK:WIFI_SEC_TYPE_SAE;
        int added=AddDeviceConfig(&c,&wifi_id);
        if(added!=WIFI_SUCCESS){printf("SB wifi config result=%d\n",added);return false;}
        configured=true;
    }
    if(GetLinkedInfo(&link)!=WIFI_SUCCESS||link.connState!=WIFI_CONNECTED) {
        dhcp_started=false;
        if(now>=wifi_retry){int rc=ConnectTo(wifi_id);printf("SB wifi connect result=%d\n",rc);wifi_retry=now+10000;}
        return false;
    }
    iface=netifapi_netif_find("wlan0");if(!iface)return false;
    if(!dhcp_started){if(netifapi_dhcp_start(iface)!=ERR_OK)return false;dhcp_started=true;}
    bool bound=dhcp_is_bound(iface)==ERR_OK;
    static bool reported;
    if(bound&&!reported){printf("SB wifi DHCP ready\n");reported=true;}
    if(!bound)reported=false;
    return bound;
}
static void synchronize_time(void) {
    SbClock before,after;uint8_t request[48]={0},response[96];int fd,n;int64_t utc;uint32_t dispersion;
    struct addrinfo hints={0},*address=NULL;struct timeval wait={3,0};fd_set reads;
    if(sb_clock_get(&before)!=SB_OK||!sb_sntp_attempt_begin(&sntp_schedule,before.uptime_ms))return;
    printf("SB SNTP attempt\n");request[0]=0x23;
    if(hi_cipher_trng_get_random_bytes(request+40,8)!=0){printf("SB SNTP nonce unavailable\n");return;}
    hints.ai_family=AF_INET;hints.ai_socktype=SOCK_DGRAM;
    if(getaddrinfo(SB_NTP_SERVER,"123",&hints,&address)!=0||!address){printf("SB SNTP DNS failed\n");return;}
    fd=socket(AF_INET,SOCK_DGRAM,0);if(fd<0){freeaddrinfo(address);return;}
    if(connect(fd,address->ai_addr,address->ai_addrlen)<0){freeaddrinfo(address);lwip_close(fd);return;}
    freeaddrinfo(address);
    if(send(fd,request,sizeof(request),0)!=(int)sizeof(request)){lwip_close(fd);return;}
    FD_ZERO(&reads);FD_SET(fd,&reads);n=select(fd+1,&reads,NULL,NULL,&wait);
    n=n>0?recv(fd,response,sizeof(response),0):-1;lwip_close(fd);
    if(n<0||sb_sntp_decode(response,(size_t)n,request+40,&utc,&dispersion)!=SB_OK){printf("SB SNTP timeout/response rejected\n");return;}
    if(sb_clock_get(&after)!=SB_OK||after.uptime_ms-before.uptime_ms>UINT32_MAX-1000-dispersion)return;
    if(sb_clock_sync(utc,(uint32_t)(after.uptime_ms-before.uptime_ms)+1000+dispersion)!=SB_OK)return;
    sb_sntp_attempt_success(&sntp_schedule,after.uptime_ms);
    printf("SB SNTP synchronized\n");
}
static bool mqtt_connect(void) {
    MQTTPacket_connectData options=MQTTPacket_connectData_initializer;char will[192];unsigned i;
    if(connect_socket()!=0){printf("SB MQTT TCP connect failed\n");return false;}
    snprintf(will,sizeof(will),"{\"node_id\":\"%s\",\"boot_id\":%lu,\"online\":false}",sb_config_get()->node_id,(unsigned long)boot);
    snprintf(up,sizeof(up),"box/%s/%s/availability",sb_config_get()->box_id,sb_config_get()->node_id);
    options.MQTTVersion=4;options.clientID.cstring=(char*)sb_config_get()->node_id;options.username.cstring=SB_MQTT_USER;options.password.cstring=SB_MQTT_PASSWORD;
    options.keepAliveInterval=60;options.cleansession=1;options.willFlag=1;options.will.topicName.cstring=up;options.will.message.cstring=will;options.will.qos=1;options.will.retained=0;
    if(MQTTConnect(&client,&options)!=SUCCESS){printf("SB MQTT authentication/connect failed\n");return false;}
    for(i=0;i<3;++i) {
        const char *suffix=i==0?"cmd":i==1?"event_ack":"box_state";
        snprintf(topic[i],sizeof(topic[i]),"box/%s/%s/%s",sb_config_get()->box_id,sb_config_get()->node_id,suffix);
        MQTTSubackData ack={0};
        if(MQTTSubscribeWithResults(&client,topic[i],QOS1,received_message,&ack)!=SUCCESS||ack.grantedQoS==SUBFAIL)return false;
    }
    connected=true;snprintf(will,sizeof(will),"{\"node_id\":\"%s\",\"boot_id\":%lu,\"online\":true}",sb_config_get()->node_id,(unsigned long)boot);
    return publish("availability",will,1)==SB_OK;
}
SbResult sb_network_poll(void) {
    SbClock now;Incoming *p;
    if(sb_clock_get(&now)!=SB_OK)return SB_PLATFORM_ERROR;
    if(now.uptime_ms>=next_diagnostic) {
        SbAppStatus status;SbSnapshot snapshot;
        printf("SB network stack_free_min=%lu\n",(unsigned long)osThreadGetStackSpace(osThreadGetId()));
        sb_app_status(&status);
        printf("SB sampling uptime=%llu samples=%llu errors=%llu missed=%llu queue=%llu interval_min=%llu interval_max=%llu\n",
               (unsigned long long)now.uptime_ms,(unsigned long long)status.sampled,
               (unsigned long long)status.sensor_errors,(unsigned long long)status.missed_periods,
               (unsigned long long)status.sample_queue_full,(unsigned long long)status.interval_min_ms,
               (unsigned long long)status.interval_max_ms);
        if(sb_pipeline_snapshot(&snapshot)==SB_OK && sb_config_get()->role==SB_ENV && snapshot.present[SB_INPUT_ENV]) {
            const SbSensorFrame *frame=&snapshot.frames[SB_INPUT_ENV];
            printf("SB ENV valid=%d source=%d temp_c=%.2f humi_pct=%.2f lux=%.2f\n",
                   (int)frame->valid,(int)frame->source,(double)frame->value.env.temperature_c,
                   (double)frame->value.env.humidity_pct,(double)frame->value.env.light_lux);
        }
        next_diagnostic=now.uptime_ms+30000;
    }
    if(!initialized) {
        if((sb_boot_reserve(SB_BOOT_FLOOR,&boot)!=SB_OK || boot>SB_BOOT_LIMIT)){printf("SB boot metadata failed; reset/provisioning required\n");next_try=UINT64_MAX;initialized=true;return SB_PLATFORM_ERROR;}
        printf("SB role=%s node=%s boot=%lu REAL sensor; GPS MOCK\n",sb_config_get()->role==SB_ENV?"ENV":"POSE",sb_config_get()->node_id,(unsigned long)boot);
        sb_wire_init(boot);NetworkInit(&network);network.mqttread=read_socket;network.mqttwrite=write_socket;
        MQTTClientInit(&client,&network,1000,send_buffer,sizeof(send_buffer),read_buffer,sizeof(read_buffer));
        initialized=true;
    }
    if(now.uptime_ms<next_try)return SB_NO_DATA;
    if(!wifi_ready(now.uptime_ms)){if(connected)disconnect();return SB_NO_DATA;}
    synchronize_time(); /* Own retry schedule also runs while MQTT remains connected. */
    if(!connected) {
        if(!mqtt_connect()){disconnect();next_try=now.uptime_ms+backoff*1000+(now.uptime_ms%201)*backoff;backoff=backoff<16?backoff*2:30;return SB_NO_DATA;}
        backoff=1;next_property=next_heartbeat=0;
        printf("SB MQTT subscribed and online\n");
    }
    if(MQTTYield(&client,10)!=SUCCESS||!client.isconnected){disconnect();return SB_NO_DATA;}
    while(count) {
        p=&received[head];
        if(p->type==0&&sb_wire_command(p->payload,p->len,p->retain,reply,sizeof(reply))==SB_OK)
            if(publish("command_ack",reply,1)!=SB_OK){disconnect();return SB_NO_DATA;}
        if(p->type==2)(void)sb_wire_box_state(p->payload,p->len);
        /* No durable events exist yet: event_ack never manufactures saved data. */
        head=(head+1)%4;--count;
    }
    if(now.uptime_ms>=next_property) {
        if(sb_wire_property(payload,sizeof(payload))!=SB_OK){disconnect();next_try=UINT64_MAX;return SB_PLATFORM_ERROR;}
        if(publish("property",payload,0)!=SB_OK){disconnect();return SB_NO_DATA;}next_property=now.uptime_ms+1000;
    }
    if(now.uptime_ms>=next_heartbeat) {
        if(sb_wire_heartbeat(payload,sizeof(payload))!=SB_OK||publish("heartbeat",payload,1)!=SB_OK){disconnect();return SB_NO_DATA;}
        SbAppStatus s;sb_app_status(&s);
        printf("SB stats uptime=%llu samples=%llu interval_min=%llu interval_max=%llu errors=%llu missed=%llu queue=%llu rx_drop=%llu\n",(unsigned long long)now.uptime_ms,(unsigned long long)s.sampled,(unsigned long long)s.interval_min_ms,(unsigned long long)s.interval_max_ms,(unsigned long long)s.sensor_errors,(unsigned long long)s.missed_periods,(unsigned long long)s.sample_queue_full,(unsigned long long)dropped);
        next_heartbeat=now.uptime_ms+30000;
    }
    return SB_OK;
}
SbResult sb_network_publish(const SbEventCandidate *item) {
    return item?SB_NOT_IMPLEMENTED:SB_INVALID_ARG; /* Reliable events belong to TASK-009. */
}
