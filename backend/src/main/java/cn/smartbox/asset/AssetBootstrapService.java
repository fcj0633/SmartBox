package cn.smartbox.asset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import cn.smartbox.auth.*;
import cn.smartbox.common.*;
import cn.smartbox.enterprise.EnterpriseViews.Route;
import cn.smartbox.enterprise.MockRouteCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit metadata initialization, separate from account/password bootstrap. */
@Service
public class AssetBootstrapService {
    private final JdbcTemplate db;private final AuthMapper users;private final Clock clock;
    private final MockRouteCatalog routes;private final ObjectMapper json;
    public AssetBootstrapService(JdbcTemplate db,AuthMapper users,Clock clock,MockRouteCatalog routes,ObjectMapper json){
        this.db=db;this.users=users;this.clock=clock;this.routes=routes;this.json=json;
    }
    private String string(JsonNode row,String key,int max){var value=row.path(key);if(!value.isTextual()||!Inputs.length(value.asText(),1,max))throw ApiException.invalid();return value.asText();}
    private UserAccount account(String username,String role){var user=users.byUsername(username);if(user==null||!Boolean.TRUE.equals(user.enabled())||!role.equals(user.roleCode()))throw ApiException.invalid();return user;}
    @Transactional public void initialize(JsonNode input){
        if(!input.isObject()||!input.path("enterprises").isArray()||!input.path("personal_boxes").isArray()||!input.path("rider_usernames").isArray())throw ApiException.invalid();
        if(input.has("simulated_boxes")&&!input.path("simulated_boxes").isArray())throw ApiException.invalid();
        if(input.has("history_tasks")&&!input.path("history_tasks").isArray())throw ApiException.invalid();
        if(input.has("demo_alarms")&&!input.path("demo_alarms").isArray())throw ApiException.invalid();
        Set<String> riders=new HashSet<>(),admins=new HashSet<>(),boxes=new HashSet<>();long now=clock.millis();
        Map<String,Long> enterpriseIds=new LinkedHashMap<>();
        for(JsonNode name:input.path("rider_usernames")){
            if(!name.isTextual()||!riders.add(name.asText()))throw ApiException.invalid();account(name.asText(),"COURIER");
        }
        for(JsonNode company:input.path("enterprises")){
            String name=string(company,"name",128),adminName=string(company,"admin_username",128);
            if(!admins.add(adminName)||!company.path("box_ids").isArray())throw ApiException.invalid();
            var admin=account(adminName,"LOGISTICS_OPERATOR");
            var prior=db.queryForList("SELECT id,name FROM enterprises WHERE admin_user_id=? FOR UPDATE",admin.id());
            long id;
            if(prior.isEmpty()){
                db.update("INSERT INTO enterprises(name,admin_user_id,created_at) VALUES(?,?,?)",name,admin.id(),now);
                id=db.queryForObject("SELECT id FROM enterprises WHERE admin_user_id=?",Long.class,admin.id());
            }else{if(!name.equals(prior.getFirst().get("name")))throw ApiException.conflict();id=((Number)prior.getFirst().get("id")).longValue();}
            enterpriseIds.put(name,id);
            for(JsonNode box:company.path("box_ids")){
                if(!box.isTextual()||!Inputs.id(box.asText())||!boxes.add(box.asText())||users.boxExists(box.asText())!=1)throw ApiException.invalid();
                ownership(box.asText(),"ENTERPRISE",id,null,now);
            }
        }
        for(JsonNode box:input.path("personal_boxes")){
            String id=string(box,"box_id",48),name=string(box,"display_name",128),ownerName=string(box,"owner_username",128);
            if(!Inputs.id(id)||!boxes.add(id)||!riders.contains(ownerName))throw ApiException.invalid();var owner=account(ownerName,"COURIER");
            // An offline demo asset is not a sensor node and has no fabricated data.
            if(users.boxExists(id)==0)db.update("INSERT INTO boxes(box_id,display_name,created_at,updated_at) VALUES(?,?,?,?)",id,name,now,now);
            ownership(id,"PERSONAL",null,owner.id(),now);
        }
        simulatedBoxes(input,enterpriseIds,boxes,now);
        historyTasks(input,enterpriseIds,now);
        demoAlarms(input,now);
    }
    private void ownership(String box,String type,Long enterprise,Long person,long now){
        var rows=db.queryForList("SELECT owner_type,enterprise_id,owner_user_id FROM box_ownerships WHERE box_id=? FOR UPDATE",box);
        if(rows.isEmpty())db.update("INSERT INTO box_ownerships(box_id,owner_type,enterprise_id,owner_user_id,created_at) VALUES(?,?,?,?,?)",box,type,enterprise,person,now);
        else{
            var row=rows.getFirst();Object e=row.get("enterprise_id"),p=row.get("owner_user_id");
            if(!type.equals(row.get("owner_type"))||!Objects.equals(enterprise,e==null?null:((Number)e).longValue())||!Objects.equals(person,p==null?null:((Number)p).longValue()))throw ApiException.conflict();
        }
    }

    /** Creates metadata-only simulated boxes and independent ENV/POSE node identities. */
    private void simulatedBoxes(JsonNode input,Map<String,Long> enterprises,Set<String> boxes,long now){
        for(JsonNode box:input.path("simulated_boxes")){
            String boxId=string(box,"box_id",48),display=string(box,"display_name",128),enterpriseName=string(box,"enterprise_name",128);
            Long enterprise=enterprises.get(enterpriseName);
            if(enterprise==null||!Inputs.id(boxId)||!boxes.add(boxId))throw ApiException.invalid();
            var existing=db.queryForList("SELECT asset_source FROM boxes WHERE box_id=?",boxId);
            if(existing.isEmpty())db.update("INSERT INTO boxes(box_id,display_name,asset_source,created_at,updated_at) VALUES(?,?,'SIMULATED',?,?)",boxId,display,now,now);
            else{
                if(!"SIMULATED".equals(existing.getFirst().get("asset_source")))throw ApiException.conflict();
                db.update("UPDATE boxes SET display_name=?,updated_at=? WHERE box_id=?",display,now,boxId);
            }
            ownership(boxId,"ENTERPRISE",enterprise,null,now);
            String envNode=string(box,"env_node_id",48);
            insertNode(envNode,boxId,"ENV",71,flag(box,"env_required",true),now);
            if(box.path("pose_node_id").isTextual()){
                String poseNode=string(box,"pose_node_id",48);
                insertNode(poseNode,boxId,"POSE",120,flag(box,"pose_required",false),now);
            }
        }
    }

    private void insertNode(String nodeId,String boxId,String role,int caps,boolean required,long now){
        var existing=db.queryForList("SELECT box_id,role,caps,required FROM nodes WHERE node_id=?",nodeId);
        if(existing.isEmpty())db.update("INSERT INTO nodes(node_id,box_id,role,caps,required,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",nodeId,boxId,role,caps,required,now,now);
        else{
            var row=existing.getFirst();
            if(!boxId.equals(row.get("box_id"))||!role.equals(row.get("role"))||((Number)row.get("caps")).intValue()!=caps)throw ApiException.conflict();
        }
    }

    /** Fixed-number COMPLETED demo tasks preserve route/track history for the map and list. */
    private void historyTasks(JsonNode input,Map<String,Long> enterprises,long now){
        for(JsonNode task:input.path("history_tasks")){
            String number=string(task,"transport_no",32),boxId=string(task,"box_id",48),enterpriseName=string(task,"enterprise_name",128);
            Long enterprise=enterprises.get(enterpriseName);
            if(enterprise==null||!Inputs.id(boxId)||!number.matches("TR-[0-9A-F]{20}"))throw ApiException.invalid();
            if(db.queryForList("SELECT 1 FROM box_ownerships WHERE box_id=? AND owner_type='ENTERPRISE' AND enterprise_id=?",boxId,enterprise).isEmpty())throw ApiException.invalid();
            if(!db.queryForList("SELECT id FROM transport_tasks WHERE transport_no=?",number).isEmpty())continue;
            String item=string(task,"item_name",50);
            String description=task.path("item_description").isTextual()?task.path("item_description").asText():null;
            if(description!=null&&!Inputs.length(description,1,200))throw ApiException.invalid();
            String routeCode=string(task,"route_code",32);
            Route route=routes.byCode(routeCode);
            if(route==null)throw ApiException.invalid();
            long started=now-Math.max(60_000,task.path("started_offset_ms").asLong(7_200_000));
            long ended=now-Math.max(0,task.path("ended_offset_ms").asLong(3_600_000));
            if(ended<=started)ended=started+60_000;
            Long admin=db.queryForObject("SELECT admin_user_id FROM enterprises WHERE id=?",Long.class,enterprise);
            db.update("INSERT INTO transport_tasks(transport_no,box_id,rider_id,operator_user_id,enterprise_id,status,"+
                    "item_name,item_description,started_at,ended_at,created_at,updated_at,route_code,route_snapshot) "+
                    "VALUES(?,?,NULL,?,?,'COMPLETED',?,?,?,?,?,?,?,?)",
                number,boxId,admin,enterprise,item,description,started,ended,started-300_000,ended,routeCode,routeJson(route));
        }
    }

    /** Seeded alarms are backed by a real event row and an evaluated alarm_state, never frontend-only. */
    private void demoAlarms(JsonNode input,long now){
        int index=0;
        Set<String> boxes=new LinkedHashSet<>();
        for(JsonNode alarm:input.path("demo_alarms")){
            String boxId=string(alarm,"box_id",48),key=string(alarm,"alarm_key",64),type=string(alarm,"type",32),level=string(alarm,"level",16);
            if(!Set.of("INFO","WARNING","CRITICAL").contains(level))throw ApiException.invalid();
            var nodeRows=db.queryForList("SELECT node_id FROM nodes WHERE box_id=? AND role='ENV' LIMIT 1",boxId);
            if(nodeRows.isEmpty())throw ApiException.invalid();
            String node=nodeRows.getFirst().get("node_id").toString();
            long seq=9000+index;
            var existing=db.queryForList("SELECT id FROM events WHERE node_id=? AND boot_id=1 AND event_seq=?",node,seq);
            long eventId;
            if(existing.isEmpty()){
                long at=now-Math.max(0,alarm.path("offset_ms").asLong(600_000));
                String data="{\"message\":"+quote(alarm.path("message").asText("模拟告警"))+"}";
                String raw="{\"v\":1,\"box_id\":"+quote(boxId)+",\"node_id\":"+quote(node)+",\"boot_id\":1,\"ts\":"+at+
                    ",\"ts_src\":\"SNTP\",\"uptime_ms\":1000,\"time_uncertainty_ms\":100,\"events\":[{\"event_seq\":"+seq+
                    ",\"type\":"+quote(type)+",\"src\":\"ENV\",\"level\":"+quote(level)+",\"data\":"+data+"}]}";
                db.update("INSERT INTO events(box_id,node_id,boot_id,event_seq,device_ts,ts_src,uptime_ms,time_uncertainty_ms,"+
                        "received_at,created_at,type,src,level,replayed,data,raw_event,content_hash) VALUES(?,?,1,?,?, 'SNTP',1000,100,?,?,?,?,?,0,?,?,?)",
                    boxId,node,seq,at,at,at,type,"ENV",level,data,raw,sha256(boxId+"|"+node+"|"+seq+"|"+type));
                eventId=db.queryForObject("SELECT id FROM events WHERE node_id=? AND boot_id=1 AND event_seq=?",Long.class,node,seq);
            }else eventId=((Number)existing.getFirst().get("id")).longValue();
            long at=now-Math.max(0,alarm.path("offset_ms").asLong(600_000));
            String details="{\"message\":"+quote(alarm.path("message").asText("模拟告警"))+"}";
            db.update("INSERT INTO alarms(box_id,alarm_key,active,level,started_at,updated_at,source_event_id,recovered_by_event_id,details) "+
                    "VALUES(?,?,1,?,?,?, ?,NULL,?) ON DUPLICATE KEY UPDATE active=1,level=VALUES(level),started_at=VALUES(started_at),"+
                    "updated_at=VALUES(updated_at),source_event_id=VALUES(source_event_id),recovered_by_event_id=NULL,details=VALUES(details)",
                boxId,key,level,at,at,eventId,details);
            boxes.add(boxId);
            index++;
        }
        for(String box:boxes){
            var highest=db.queryForList("SELECT level FROM alarms WHERE box_id=? AND active=1 ORDER BY CASE level WHEN 'CRITICAL' THEN 3 WHEN 'WARNING' THEN 2 ELSE 1 END DESC LIMIT 1",box);
            if(highest.isEmpty())db.update("INSERT INTO alarm_state(box_id,assessment,active,level,updated_at) VALUES(?,'EVALUATED',0,NULL,?) ON DUPLICATE KEY UPDATE assessment='EVALUATED',active=0,level=NULL,updated_at=VALUES(updated_at)",box,now);
            else db.update("INSERT INTO alarm_state(box_id,assessment,active,level,updated_at) VALUES(?,'EVALUATED',1,?,?) ON DUPLICATE KEY UPDATE assessment='EVALUATED',active=1,level=VALUES(level),updated_at=VALUES(updated_at)",box,highest.getFirst().get("level").toString(),now);
        }
    }

    private String routeJson(Route route){try{return json.writeValueAsString(route);}catch(Exception invalid){throw new IllegalStateException("Route snapshot serialization failed");}}
    private static String quote(String value){try{return new ObjectMapper().writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException();}}
    private static byte[] sha256(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception invalid){throw new IllegalStateException();}}
    private static boolean flag(JsonNode node,String key,boolean fallback){return node.has(key)?node.path(key).asBoolean(fallback):fallback;}
}
