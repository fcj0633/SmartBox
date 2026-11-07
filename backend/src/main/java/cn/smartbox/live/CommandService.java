package cn.smartbox.live;

import java.time.Clock;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.authorization.*;
import cn.smartbox.audit.AuditService;
import cn.smartbox.common.ApiException;

@Service
public class CommandService {
    private final LiveStore db;private final ContractValidator json;private final BoxAccessService access;
    private final TransactionTemplate tx;private final Clock clock;private final AuditService audit;private final MqttGateway gateway;
    private final TestFaults faults;
    public CommandService(LiveStore db,ContractValidator json,BoxAccessService access,PlatformTransactionManager manager,Clock clock,AuditService audit,MqttGateway gateway,TestFaults faults){
        this.db=db;this.json=json;this.access=access;this.tx=new TransactionTemplate(manager);this.clock=clock;this.audit=audit;this.gateway=gateway;
        this.faults=faults;
    }
    public String submit(CurrentUser user,String box,String key,JsonNode input){
        String cmd=input.path("cmd").asText();
        access.require(user,box,cmd.equals("sentry")?PermissionPolicy.Action.SENTRY_CONTROL:PermissionPolicy.Action.CONFIGURE);
        if(key==null||key.getBytes(StandardCharsets.UTF_8).length<1||key.getBytes(StandardCharsets.UTF_8).length>64)throw ApiException.invalid();
        json.validate("CommandInput",input);
        // Unknown privilege-bearing fields are rejected rather than trusted.
        for(String forbidden:List.of("role","caps","target_caps","box_ids","node_id"))if(input.has(forbidden))throw ApiException.invalid();
        ObjectNode parameters=JsonNodeFactory.instance.objectNode();
        List<String> parameterFields=switch(cmd){case "sentry"->List.of("on","duration_s");case "profile"->List.of("profile_id","temp_min_c","temp_max_c","humi_min_pct","humi_max_pct","temp_hysteresis_c","humi_hysteresis_pct");case "gps_mode"->List.of("mode","seed");case "output"->List.of("kind","on","duration_s");case "calibrate"->List.of();default->throw ApiException.invalid();};
        for(String field:parameterFields)parameters.set(field,input.path("params").get(field));
        ObjectNode canonical=JsonNodeFactory.instance.objectNode();canonical.set("cmd",input.get("cmd"));canonical.set("params",parameters);if(input.has("target_node_id"))canonical.set("target_node_id",input.get("target_node_id"));
        byte[] fingerprint=json.hash(canonical);long now=clock.millis();
        String operation=tx.execute(status->{
            var locked=db.one("SELECT * FROM boxes WHERE box_id=? FOR UPDATE",box);
            var existing=db.one("SELECT * FROM idempotency_records WHERE user_id=? AND box_id=? AND idempotency_key=?",user.id(),box,key.getBytes(StandardCharsets.UTF_8));
            if(existing!=null){
                if(((Number)existing.get("expires_at")).longValue()>now){if(!Arrays.equals((byte[])existing.get("request_hash"),fingerprint))throw ApiException.conflict();return existing.get("operation_id").toString();}
                db.update("DELETE FROM idempotency_records WHERE user_id=? AND box_id=? AND idempotency_key=?",user.id(),box,key.getBytes(StandardCharsets.UTF_8));
            }
            int required=switch(cmd){case "sentry"->64;case "profile"->1;case "gps_mode"->32;case "calibrate"->8;case "output"->switch(input.path("params").path("kind").asText()){case "light"->2;case "fan"->4;case "indicator"->16;default->throw ApiException.invalid();};default->throw ApiException.invalid();};
            List<Map<String,Object>> targets=new ArrayList<>();
            for(var node:db.rows("SELECT n.*,r.ts_src FROM nodes n LEFT JOIN node_runtime r ON n.node_id=r.node_id WHERE n.box_id=?",box)){
                boolean specified=input.has("target_node_id"),matches=!specified||input.path("target_node_id").asText().equals(node.get("node_id"));
                boolean capable=(((Number)node.get("caps")).intValue()&required)==required;
                if(cmd.equals("sentry")){
                    if(Boolean.TRUE.equals(node.get("required"))&&capable)targets.add(node);
                }else if(matches&&capable)targets.add(node);
            }
            if(targets.isEmpty()||input.has("target_node_id")&&targets.stream().noneMatch(n->input.path("target_node_id").asText().equals(n.get("node_id"))))throw ApiException.invalid();
            for(var target:targets)if(!"SNTP".equals(target.get("ts_src")))throw new ApiException(409,"TIME_UNSYNCED","目标节点尚无可靠时间");
            String id=UUID.randomUUID().toString();long deadline=now+(cmd.equals("sentry")?10000:15000);
            ObjectNode params=parameters.deepCopy();
            if(cmd.equals("sentry")){
                var revision=new BigDecimal(locked.get("sentry_revision").toString()).toBigInteger().add(java.math.BigInteger.ONE);
                if(revision.bitLength()>64)throw ApiException.conflict();params.put("sentry_revision",revision);
                db.update("UPDATE boxes SET sentry_revision=?,updated_at=? WHERE box_id=?",new BigDecimal(revision),now,box);
            }
            db.update("INSERT INTO command_operations(operation_id,box_id,requested_by,cmd,params,status,created_at,updated_at,deadline_at) VALUES(?,?,?,?,?,'PENDING',?,?,?)",id,box,user.id(),cmd,json.text(params),now,now,deadline);
            for(var target:targets){
                String request=UUID.randomUUID().toString(),node=target.get("node_id").toString();
                db.update("INSERT INTO node_commands(request_id,operation_id,box_id,node_id,target_caps,issued_at,expires_at,created_at,updated_at,target_boot_id) VALUES(?,?,?,?,?,?,?,?,?,?)",request,id,box,node,required,now,now+15000,now,now,target.get("current_boot_id"));
                ObjectNode payload=JsonNodeFactory.instance.objectNode();payload.put("v",1);payload.put("request_id",request);payload.put("operation_id",id);payload.put("box_id",box);payload.put("node_id",node);payload.put("cmd",cmd);payload.put("target_caps",required);payload.put("issued_at",now);payload.put("expires_at",now+15000);payload.set("params",params);
                json.validate("Command",payload);
                if(json.text(payload).getBytes(StandardCharsets.UTF_8).length>1536)throw ApiException.invalid();
                db.update("INSERT INTO command_dispatch(request_id,topic,payload,next_attempt_at) VALUES(?,?,?,?)",request,"box/"+box+"/"+node+"/cmd",json.text(payload),now);
            }
            db.update("INSERT INTO idempotency_records(user_id,box_id,idempotency_key,request_hash,operation_id,created_at,expires_at) VALUES(?,?,?,?,?,?,?)",user.id(),box,key.getBytes(StandardCharsets.UTF_8),fingerprint,id,now,now+86400000);
            return id;
        });
        audit.record(user.id(),null,box,"COMMAND","ACCEPTED");return operation;
    }
    public ObjectNode operation(CurrentUser user,String box,String operation){
        access.require(user,box,PermissionPolicy.Action.SENTRY_CONTROL);
        var row=db.one("SELECT * FROM command_operations WHERE operation_id=? AND box_id=?",operation,box);
        if(row==null)throw new ApiException(404,"NOT_FOUND","操作不存在");
        if(user.role().equals("COURIER")&&!row.get("cmd").equals("sentry"))throw ApiException.denied();
        ObjectNode result=JsonNodeFactory.instance.objectNode();result.put("operation_id",operation);result.put("box_id",box);result.put("status",row.get("status").toString());
        ArrayNode nodes=result.putArray("nodes");
        for(var node:db.rows("SELECT * FROM node_commands WHERE operation_id=? ORDER BY node_id",operation)){
            ObjectNode item=nodes.addObject();item.put("node_id",node.get("node_id").toString());item.put("request_id",node.get("request_id").toString());item.put("status",node.get("status").toString());
            if(node.get("code")==null)item.putNull("code");else item.put("code",node.get("code").toString());
            item.set("actual_state",node.get("actual_state")==null?JsonNodeFactory.instance.objectNode():json.actualState(json.json(node.get("actual_state"))));
        }
        return result;
    }
    public void receipt(JsonNode value,long now){
        var command=db.one("SELECT c.*,o.cmd,o.params,o.deadline_at,o.finalized_at,o.operation_order FROM node_commands c JOIN command_operations o ON c.operation_id=o.operation_id WHERE c.request_id=? FOR UPDATE",value.path("request_id").asText());
        if(command==null||!value.path("operation_id").asText().equals(command.get("operation_id"))||!value.path("node_id").asText().equals(command.get("node_id"))||!value.path("box_id").asText().equals(command.get("box_id"))||value.path("boot_id").asLong()!=((Number)command.get("target_boot_id")).longValue())throw ApiException.invalid();
        // A receipt may arrive late, but its claimed execution timestamp must be within the original lifetime.
        if(!value.path("ts_src").asText().equals("SNTP")||value.path("ts").asLong()<((Number)command.get("issued_at")).longValue()||value.path("ts").asLong()>((Number)command.get("expires_at")).longValue())throw ApiException.invalid();
        if(value.path("result").asText().equals("ok")){
            JsonNode params=json.json(command.get("params")),actual=value.path("actual_state");
            boolean matches=switch(command.get("cmd").toString()){
                case "profile"->actual.path("profile_id").asText().equals(params.path("profile_id").asText());
                case "gps_mode"->actual.path("mode").asText().equals(params.path("mode").asText());
                case "calibrate"->actual.path("calibrated").isBoolean()&&actual.path("calibrated").asBoolean();
                case "output"->actual.path("kind").asText().equals(params.path("kind").asText())&&actual.path("on").equals(params.path("on"));
                case "sentry"->actual.path("sentry").asText().equals(params.path("on").asBoolean()?"ARMED":"DISARMED")&&actual.path("sentry_revision").bigIntegerValue().equals(params.path("sentry_revision").bigIntegerValue());
                default->false;
            };
            if(!matches)throw ApiException.invalid();
        }
        if(db.count("SELECT COUNT(*) FROM command_receipts WHERE request_id=? AND content_hash=?",command.get("request_id"),json.hash(value))>0)return;
        if(command.get("finalized_at")==null&&now>=((Number)command.get("deadline_at")).longValue())aggregate(command.get("operation_id").toString(),now);
        db.update("INSERT INTO command_receipts(request_id,content_hash,received_at,payload) VALUES(?,?,?,?)",command.get("request_id"),json.hash(value),now,json.text(value));
        db.update("UPDATE command_dispatch SET status='DONE',lock_owner=NULL,locked_until=NULL WHERE request_id=?",command.get("request_id"));
        String result=value.path("result").asText();String state=result.equals("ok")?"SUCCEEDED":result.equals("expired")?"EXPIRED":"FAILED";
        // Node execution facts remain visible even after the immutable operation outcome.
        db.update("UPDATE node_commands SET status=?,ack_boot_id=?,ack_received_at=?,ack_ts=?,ack_ts_src=?,ack_uptime_ms=?,ack_time_uncertainty_ms=?,result=?,code=?,actual_state=?,updated_at=? WHERE request_id=?",
            state,value.path("boot_id").asLong(),now,value.path("ts").asLong(),value.path("ts_src").asText(),new BigDecimal(value.path("uptime_ms").bigIntegerValue()),value.path("time_uncertainty_ms").asLong(),result,value.path("code").asText(),json.text(value.path("actual_state")),now,command.get("request_id"));
        if(result.equals("ok")){
            var current=db.one("SELECT current_boot_id FROM nodes WHERE node_id=?",command.get("node_id"));
            long newer=db.count("SELECT COUNT(*) FROM node_commands n JOIN command_operations o ON n.operation_id=o.operation_id WHERE n.node_id=? AND o.cmd=? AND o.operation_order>?",command.get("node_id"),command.get("cmd"),command.get("operation_order"));
            boolean revision=true;
            if(command.get("cmd").equals("sentry")){
                JsonNode params=json.json(command.get("params"));var box=db.one("SELECT sentry_revision FROM boxes WHERE box_id=?",command.get("box_id"));
                revision=params.path("sentry_revision").bigIntegerValue().equals(new BigDecimal(box.get("sentry_revision").toString()).toBigInteger());
            }
            if(current.get("current_boot_id")!=null&&((Number)current.get("current_boot_id")).longValue()==value.path("boot_id").asLong()&&newer==0&&revision){
                var runtime=db.one("SELECT actual_state FROM node_runtime WHERE node_id=?",command.get("node_id"));
                ObjectNode actual=runtime==null||runtime.get("actual_state")==null?JsonNodeFactory.instance.objectNode():(ObjectNode)json.json(runtime.get("actual_state"));
                value.path("actual_state").fields().forEachRemaining(e->actual.set(e.getKey(),e.getValue()));
                db.update("UPDATE node_runtime SET actual_state=? WHERE node_id=?",json.text(actual),command.get("node_id"));
                if(command.get("cmd").equals("profile")){
                    JsonNode params=json.json(command.get("params"));
                    if(!params.path("profile_id").asText().equals(value.path("actual_state").path("profile_id").asText()))throw ApiException.invalid();
                    db.update("UPDATE node_runtime SET applied_profile=?,applied_profile_request=? WHERE node_id=?",json.text(params),command.get("request_id"),command.get("node_id"));
                    db.update("UPDATE boxes SET profile_config=?,updated_at=? WHERE box_id=?",json.text(params),now,command.get("box_id"));
                }
            }
        }
        aggregate(command.get("operation_id").toString(),now);
    }
    public static String outcome(List<String> states,boolean expired){
        long ok=states.stream().filter("SUCCEEDED"::equals).count();boolean pending=states.stream().anyMatch(s->s.equals("PENDING")||s.equals("RUNNING"));
        if(ok==states.size())return "SUCCEEDED";
        if(pending&&!expired)return "RUNNING";
        if(ok>0)return "PARTIAL";
        if(pending||states.contains("EXPIRED"))return "EXPIRED";
        return "FAILED";
    }
    private void aggregate(String operation,long now){
        var row=db.one("SELECT * FROM command_operations WHERE operation_id=? FOR UPDATE",operation);
        if(row==null||row.get("finalized_at")!=null)return;
        boolean expired=now>=((Number)row.get("deadline_at")).longValue();
        List<String> states=db.rows("SELECT status FROM node_commands WHERE operation_id=?",operation).stream().map(n->n.get("status").toString()).toList();
        String outcome=outcome(states,expired);boolean terminal=!outcome.equals("RUNNING");
        db.update("UPDATE command_operations SET status=?,finalized_at=?,updated_at=? WHERE operation_id=?",outcome,terminal?now:null,now,operation);
    }
    @Scheduled(fixedDelay=300)
    public void work(){
        if(!gateway.enabled())return;
        long now=clock.millis();
        try{
            refreshOutcomes();
            if(faults.active("SMARTBOX_TEST_PAUSE_DISPATCH_FILE"))return;
            if(!gateway.ready())return;
            for(int i=0;i<8;i++){
                String owner=UUID.randomUUID().toString();
                var work=tx.execute(status->{
                    var row=db.one("SELECT d.*,c.expires_at FROM command_dispatch d JOIN node_commands c ON c.request_id=d.request_id WHERE d.status<>'DONE' AND d.next_attempt_at<=? AND (d.locked_until IS NULL OR d.locked_until<=?) ORDER BY d.next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED",now,now);
                    if(row!=null)db.update("UPDATE command_dispatch SET lock_owner=?,locked_until=?,attempts=attempts+1 WHERE request_id=?",owner,now+10000,row.get("request_id"));return row;
                });
                if(work==null)break;
                boolean expired=now>=((Number)work.get("expires_at")).longValue();
                if(!expired)gateway.publish(work.get("topic").toString(),work.get("payload").toString().getBytes(StandardCharsets.UTF_8));
                // Repeat the same request until execution receipt, not merely MQTT PUBACK.
                db.update("UPDATE command_dispatch SET status=?,next_attempt_at=?,locked_until=NULL,lock_owner=NULL WHERE request_id=? AND lock_owner=?",expired?"DONE":"PENDING",now+2000,work.get("request_id"),owner);
                if(expired)db.update("UPDATE node_commands SET status='EXPIRED',updated_at=? WHERE request_id=? AND status IN ('PENDING','RUNNING')",now,work.get("request_id"));
            }
        }catch(Exception error){audit.record(null,null,null,"COMMAND_WORK","UNAVAILABLE");}
    }
    public void refreshOutcomes(){long now=clock.millis();for(var operation:db.rows("SELECT operation_id FROM command_operations WHERE finalized_at IS NULL"))tx.executeWithoutResult(s->aggregate(operation.get("operation_id").toString(),now));}
}
