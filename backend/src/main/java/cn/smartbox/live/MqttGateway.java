package cn.smartbox.live;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;

/** One client owner, bounded callback handoff, no SQL on the MQTT callback thread. */
@Component
public class MqttGateway {
    public record Packet(String topic,byte[] payload,int qos,boolean retained){}
    private final ArrayBlockingQueue<Packet> incoming=new ArrayBlockingQueue<>(256);
    public final AtomicLong dropped=new AtomicLong();
    private final MqttSettings settings;private final LiveStore store;private MqttAsyncClient client;
    private boolean ready;private long nextConnect;
    public MqttGateway(MqttSettings settings,LiveStore store){this.settings=settings;this.store=store;}
    public boolean enabled(){return settings.enabled();}
    public boolean ready(){return ready&&client!=null&&client.isConnected();}
    public Packet poll(){return incoming.poll();}
    @Scheduled(fixedDelay=1000)
    public synchronized void connect(){
        if(!enabled()||ready()||System.currentTimeMillis()<nextConnect)return;
        nextConnect=System.currentTimeMillis()+3000;ready=false;
        try{
            if(client!=null)client.close(true);
            String username=settings.user();
            String endpoint=settings.endpoint();
            MqttTransport.validate(endpoint);
            client=new MqttAsyncClient(endpoint,settings.clientId(),new MemoryPersistence());
            client.setCallback(new MqttCallback(){
                public void connectionLost(Throwable cause){ready=false;}
                public void deliveryComplete(IMqttDeliveryToken token){}
                public void messageArrived(String topic,MqttMessage message){
                    enqueue(topic,message.getPayload(),message.getQos(),message.isRetained());
                }
            });
            MqttConnectOptions options=new MqttConnectOptions();options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setCleanSession(true);options.setKeepAliveInterval(60);options.setConnectionTimeout(3);
            options.setUserName(username);options.setPassword(settings.password().toCharArray());
            options.setSocketFactory(MqttTransport.trust(settings.ca()));
            options.setHttpsHostnameVerificationEnabled(true);
            options.setConnectionTimeout(10);
            client.connect(options).waitForCompletion(12000);
            List<String> topics=new ArrayList<>();
            for(var node:store.rows("SELECT n.node_id,n.box_id FROM nodes n JOIN boxes b ON b.box_id=n.box_id WHERE b.asset_source='REAL'"))for(String suffix:List.of("property","heartbeat","event","command_ack","availability"))
                topics.add("box/"+node.get("box_id")+"/"+node.get("node_id")+"/"+suffix);
            if(!topics.isEmpty())client.subscribe(topics.toArray(String[]::new),topics.stream().mapToInt(x->1).toArray()).waitForCompletion(4000);
            ready=true;
            org.slf4j.LoggerFactory.getLogger(MqttGateway.class).info("MQTT connected to fixed remote TLS broker; subscribed {} REAL node topics",topics.size());
        }catch(Exception error){
            ready=false;
            org.slf4j.LoggerFactory.getLogger(MqttGateway.class).warn("MQTT connect failed ({})",failureCategory(error));
        }
    }
    static String failureCategory(Throwable error) {
        for(Throwable x=error;x!=null;x=x.getCause()) {
            if(x instanceof java.net.UnknownHostException)return "DNS";
            if(x instanceof javax.net.ssl.SSLException || x instanceof java.security.cert.CertificateException)return "TLS";
            if(x instanceof java.net.SocketTimeoutException || x instanceof java.net.ConnectException)return "NETWORK";
            if(x instanceof MqttException m && (m.getReasonCode()==4 || m.getReasonCode()==5))return "AUTHENTICATION";
        }
        return "CONNECTION_OR_SUBSCRIPTION";
    }
    public synchronized boolean publish(String topic,byte[] data){
        if(!ready())return false;
        try{client.publish(topic,data,1,false).waitForCompletion(2000);return true;}catch(Exception error){return false;}
    }
    boolean enqueue(String topic,byte[] payload,int qos,boolean retained){
        if(payload.length>1536){dropped.incrementAndGet();return false;}
        boolean accepted=incoming.offer(new Packet(topic,payload.clone(),qos,retained));
        if(!accepted)dropped.incrementAndGet();return accepted;
    }
    @PreDestroy public synchronized void stop(){ready=false;try{if(client!=null){if(client.isConnected())client.disconnect().waitForCompletion(2000);client.close(true);}}catch(Exception ignored){}}
}
