package cn.smartbox.live;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;

/** One client owner, bounded callback handoff, no SQL on the MQTT callback thread. */
@Component
public class MqttGateway {
    public record Packet(String topic,byte[] payload,int qos,boolean retained){}
    private final ArrayBlockingQueue<Packet> incoming=new ArrayBlockingQueue<>(256);
    public final AtomicLong dropped=new AtomicLong();
    private final Environment env;private final LiveStore store;private MqttAsyncClient client;
    private boolean ready;private long nextConnect;
    public MqttGateway(Environment env,LiveStore store){
        this.env=env;this.store=store;
        if(enabled()&&(!"smartbox_backend".equals(env.getRequiredProperty("SMARTBOX_MQTT_USER"))||env.getRequiredProperty("SMARTBOX_MQTT_PASSWORD").isBlank()||!env.getProperty("SMARTBOX_MQTT_URL","tcp://127.0.0.1:1883").equals("tcp://127.0.0.1:1883")))throw new IllegalArgumentException("Invalid project MQTT configuration");
    }
    public boolean enabled(){return env.getProperty("SMARTBOX_MQTT_ENABLED",Boolean.class,false);}
    public boolean ready(){return ready&&client!=null&&client.isConnected();}
    public Packet poll(){return incoming.poll();}
    @Scheduled(fixedDelay=1000)
    public synchronized void connect(){
        if(!enabled()||ready()||System.currentTimeMillis()<nextConnect)return;
        nextConnect=System.currentTimeMillis()+3000;ready=false;
        try{
            if(client!=null)client.close(true);
            String username=env.getRequiredProperty("SMARTBOX_MQTT_USER");
            String endpoint=env.getProperty("SMARTBOX_MQTT_URL","tcp://127.0.0.1:1883");
            if(!endpoint.equals("tcp://127.0.0.1:1883"))throw new IllegalArgumentException("Local MQTT endpoint required");
            client=new MqttAsyncClient(endpoint,username,new MemoryPersistence());
            client.setCallback(new MqttCallback(){
                public void connectionLost(Throwable cause){ready=false;}
                public void deliveryComplete(IMqttDeliveryToken token){}
                public void messageArrived(String topic,MqttMessage message){
                    enqueue(topic,message.getPayload(),message.getQos(),message.isRetained());
                }
            });
            MqttConnectOptions options=new MqttConnectOptions();options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setCleanSession(true);options.setKeepAliveInterval(60);options.setConnectionTimeout(3);
            options.setUserName(username);options.setPassword(env.getRequiredProperty("SMARTBOX_MQTT_PASSWORD").toCharArray());
            client.connect(options).waitForCompletion(4000);
            List<String> topics=new ArrayList<>();
            for(var node:store.rows("SELECT node_id,box_id FROM nodes"))for(String suffix:List.of("property","heartbeat","event","command_ack","availability"))
                topics.add("box/"+node.get("box_id")+"/"+node.get("node_id")+"/"+suffix);
            if(!topics.isEmpty())client.subscribe(topics.toArray(String[]::new),topics.stream().mapToInt(x->1).toArray()).waitForCompletion(4000);
            ready=true;
        }catch(Exception error){ready=false;}
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
