package cn.smartbox.live;

import java.math.*;
import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import cn.smartbox.audit.AuditService;
import cn.smartbox.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestionService {
    private final LiveStore db;private final ContractValidator json;private final MqttGateway gateway;
    private final TransactionTemplate tx;private final Clock clock;private final AuditService audit;private final CommandService commands;
    private long observedDrops;
    private final TestFaults faults;
    public IngestionService(LiveStore db,ContractValidator json,MqttGateway gateway,PlatformTransactionManager manager,Clock clock,AuditService audit,CommandService commands,TestFaults faults){
        this.db=db;this.json=json;this.gateway=gateway;this.tx=new TransactionTemplate(manager);this.clock=clock;this.audit=audit;this.commands=commands;
        this.faults=faults;
    }
    @Scheduled(fixedDelay=20)
    public void drain(){
        long dropped=gateway.dropped.get();if(dropped!=observedDrops){observedDrops=dropped;audit.record(null,null,null,"MQTT_QUEUE","DROPPED");}
        for(int i=0;i<32;i++){
            var packet=gateway.poll();if(packet==null)return;
            try{accept(packet);}catch(Exception error){audit.record(null,null,null,"MQTT_REJECTED",error instanceof ApiException?"INVALID":"UNAVAILABLE");}
        }
    }
    public void accept(MqttGateway.Packet packet){
        JsonNode value=json.mqtt(packet.topic(),packet.payload(),packet.qos(),packet.retained());
        String[] topic=packet.topic().split("/");String node=topic[2],box=topic[1],suffix=topic[3];
        var registration=db.one("SELECT * FROM nodes WHERE node_id=? AND box_id=?",node,box);
        if(registration==null)throw ApiException.invalid();
        if(value.has("role")&&(!value.path("role").asText().equals(registration.get("role"))||value.path("caps").asInt()!=((Number)registration.get("caps")).intValue()))throw ApiException.invalid();
        if(suffix.equals("event")){
            Set<Long> unique=new HashSet<>();int caps=((Number)registration.get("caps")).intValue();
            for(JsonNode event:value.path("events")){
                if(!unique.add(event.path("event_seq").asLong()))throw ApiException.invalid();
                String kind=event.path("type").asText();int needed=kind.startsWith("ENV_")?1:Set.of("POSE_CHANGED","IMPACT").contains(kind)?8:kind.startsWith("GEOFENCE_")?32:kind.equals("SENTRY_INTRUSION")?64:kind.equals("DOOR_EVIDENCE")?(event.path("src").asText().equals("LIGHT")?1:event.path("src").asText().equals("TILT")?8:0):0;
                if((caps&needed)!=needed)throw ApiException.invalid();
            }
        }
        long now=clock.millis();
        List<Long> stored=tx.execute(status->{
            var locked=db.one("SELECT * FROM nodes WHERE node_id=? FOR UPDATE",node);
            long boot=value.path("boot_id").asLong();Number current=(Number)locked.get("current_boot_id");
            boolean older=current!=null&&boot<current.longValue();
            if(!older&&!suffix.equals("event")&&!suffix.equals("command_ack"))activity(value,suffix,node,boot,now,current);
            if(suffix.equals("event")){
                if(current!=null&&boot==current.longValue())activity(value,suffix,node,boot,now,current);
                return events(value,node,box,now);
            }
            if(suffix.equals("property")){
                telemetry(value,node,box,now,older);
            }else if(suffix.equals("command_ack"))commands.receipt(value,now);
            return List.<Long>of();
        });
        if(suffix.equals("event")&&!stored.isEmpty()){
            if(faults.active("SMARTBOX_TEST_DROP_ACK_FILE"))return;
            ObjectNode ack=JsonNodeFactory.instance.objectNode();ack.put("v",1);ack.put("box_id",box);ack.put("node_id",node);
            ack.put("boot_id",value.path("boot_id").asLong());ack.put("result","stored");ack.put("ts",now);
            ArrayNode seqs=ack.putArray("event_seqs");stored.forEach(seqs::add);
            gateway.publish("box/"+box+"/"+node+"/event_ack",json.text(ack).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private void activity(JsonNode value,String suffix,String node,long boot,long now,Number current){
        boolean availability=suffix.equals("availability"), online=!availability||value.path("online").asBoolean();
        // An offline will cannot establish a previously unseen generation.
        if(availability&&!online&&(current==null||boot!=current.longValue()))return;
        var runtime=db.one("SELECT * FROM node_runtime WHERE node_id=?",node);
        BigInteger uptime=value.has("uptime_ms")?value.path("uptime_ms").bigIntegerValue():BigInteger.ZERO;
        if(runtime!=null&&boot==((Number)runtime.get("boot_id")).longValue()&&!availability&&uptime.compareTo(new BigDecimal(runtime.get("uptime_ms").toString()).toBigInteger())<0)return;
        String heartbeat=suffix.equals("heartbeat")?json.text(value):null;
        if(runtime==null||boot!=((Number)runtime.get("boot_id")).longValue()){
            db.update("DELETE FROM node_runtime WHERE node_id=?",node);
            db.update("INSERT INTO node_runtime(node_id,boot_id,online,last_seen,uptime_ms,ts_src,heartbeat) VALUES(?,?,?,?,?,?,?)",node,boot,online,now,new BigDecimal(uptime),value.path("ts_src").asText("UNSYNCED"),heartbeat);
        }else if(availability){db.update("UPDATE node_runtime SET online=?,last_seen=? WHERE node_id=?",online,now,node);}
        else db.update("UPDATE node_runtime SET online=1,last_seen=?,uptime_ms=?,ts_src=?,heartbeat=COALESCE(?,heartbeat) WHERE node_id=?",now,new BigDecimal(uptime),value.path("ts_src").asText(),heartbeat,node);
        if(heartbeat!=null){
            var row=db.one("SELECT actual_state FROM node_runtime WHERE node_id=?",node);
            ObjectNode state=row.get("actual_state")==null?JsonNodeFactory.instance.objectNode():(ObjectNode)json.json(row.get("actual_state"));
            JsonNode sentry=value.path("sentry");
            if(!state.has("sentry_revision")||sentry.path("revision").bigIntegerValue().compareTo(state.path("sentry_revision").bigIntegerValue())>=0){state.set("sentry",sentry.path("state"));state.set("sentry_revision",sentry.path("revision"));db.update("UPDATE node_runtime SET actual_state=? WHERE node_id=?",json.text(state),node);}
        }
        db.update("UPDATE nodes SET current_boot_id=?,last_received_at=?,updated_at=? WHERE node_id=?",boot,now,now,node);
    }
    private Object nullable(JsonNode node,String field){return node.path(field).isNull()||node.get(field)==null?null:node.get(field).asLong();}
    private void telemetry(JsonNode value,String node,String box,long now,boolean older){
        long boot=value.path("boot_id").asLong(),seq=value.path("property_seq").asLong();
        var previous=db.one("SELECT payload FROM telemetry WHERE node_id=? AND boot_id=? AND property_seq=?",node,boot,seq);
        if(previous!=null){if(!ContractValidator.canonical(json.json(previous.get("payload"))).equals(ContractValidator.canonical(value)))throw ApiException.conflict();return;}
        Object[] fields={box,node,boot,seq,nullable(value,"ts"),value.path("ts_src").asText(),new BigDecimal(value.path("uptime_ms").bigIntegerValue()),nullable(value,"time_uncertainty_ms"),now,json.text(value),now};
        db.update("INSERT INTO telemetry(box_id,node_id,boot_id,property_seq,device_ts,ts_src,uptime_ms,time_uncertainty_ms,received_at,payload,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",fields);
        var latest=db.one("SELECT * FROM node_latest WHERE node_id=?",node);
        if(!older&&(latest==null||boot>((Number)latest.get("boot_id")).longValue()||boot==((Number)latest.get("boot_id")).longValue()&&seq>((Number)latest.get("property_seq")).longValue())){
            db.update("DELETE FROM node_latest WHERE node_id=?",node);
            db.update("INSERT INTO node_latest(box_id,node_id,boot_id,property_seq,device_ts,ts_src,uptime_ms,time_uncertainty_ms,received_at,payload,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",fields);
        }
        JsonNode loc=value.path("loc"),observation=loc.path("observation");
        if(observation.isObject()){
            var existing=db.one("SELECT payload FROM location_samples WHERE node_id=? AND boot_id=? AND sample_seq=?",node,boot,observation.path("sample_seq").asLong());
            if(existing!=null){if(!ContractValidator.canonical(json.json(existing.get("payload"))).equals(ContractValidator.canonical(loc)))throw ApiException.conflict();}
            else db.update("INSERT INTO location_samples(node_id,box_id,boot_id,sample_seq,device_ts,ts_src,uptime_ms,received_at,payload) VALUES(?,?,?,?,?,?,?,?,?)",node,box,boot,observation.path("sample_seq").asLong(),nullable(observation,"ts"),observation.path("ts_src").asText(),new BigDecimal(observation.path("uptime_ms").bigIntegerValue()),now,json.text(loc));
        }
    }
    private List<Long> events(JsonNode value,String node,String box,long now){
        List<Long> keys=new ArrayList<>();
        for(JsonNode event:value.path("events")){
            ObjectNode content=JsonNodeFactory.instance.objectNode();for(String field:List.of("event_seq","ts","ts_src","uptime_ms","time_uncertainty_ms","type","src","level","data"))content.set(field,event.get(field));content.put("node_id",node);content.put("box_id",box);content.put("boot_id",value.path("boot_id").asLong());
            byte[] hash=json.hash(content);long seq=event.path("event_seq").asLong(),boot=value.path("boot_id").asLong();
            var existing=db.one("SELECT content_hash FROM events WHERE node_id=? AND boot_id=? AND event_seq=?",node,boot,seq);
            if(existing!=null){if(!Arrays.equals((byte[])existing.get("content_hash"),hash))throw ApiException.conflict();}
            else{
                db.update("INSERT INTO events(box_id,node_id,boot_id,event_seq,device_ts,ts_src,uptime_ms,time_uncertainty_ms,received_at,created_at,type,src,level,replayed,data,raw_event,content_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    box,node,boot,seq,nullable(event,"ts"),event.path("ts_src").asText(),new BigDecimal(event.path("uptime_ms").bigIntegerValue()),nullable(event,"time_uncertainty_ms"),now,now,event.path("type").asText(),event.path("src").asText(),event.path("level").asText(),event.path("replayed").asBoolean(),json.text(event.path("data")),json.text(event),hash);
                long id=((Number)db.one("SELECT id FROM events WHERE node_id=? AND boot_id=? AND event_seq=?",node,boot,seq).get("id")).longValue();
                db.update("INSERT INTO processing_outbox(event_id,task_type,next_attempt_at,created_at,updated_at) VALUES(?,'EVENT_VIEW',?,?,?)",id,now,now,now);
            }
            if(!keys.contains(seq))keys.add(seq);
        }
        return keys;
    }
}
