package cn.smartbox.live;

import java.time.Clock;
import java.math.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import cn.smartbox.auth.*;
import cn.smartbox.authorization.*;
import cn.smartbox.audit.AuditService;
import cn.smartbox.common.ApiException;

@Service
public class QueryService {
    private final LiveStore db;private final ContractValidator json;private final BoxAccessService access;
    private final AuthMapper users;private final Clock clock;private final AuditService audit;
    public QueryService(LiveStore db,ContractValidator json,BoxAccessService access,AuthMapper users,Clock clock,AuditService audit){this.db=db;this.json=json;this.access=access;this.users=users;this.clock=clock;this.audit=audit;}
    public boolean online(Map<String,Object> row){return row.get("online")!=null&&(Boolean.TRUE.equals(row.get("online"))||row.get("online").equals(1))&&clock.millis()-((Number)row.get("last_seen")).longValue()<90000;}
    public List<ObjectNode> boxes(CurrentUser user){
        List<ObjectNode> result=new ArrayList<>();for(String box:users.boxes(user.id())){
            result.add(snapshot(user,box));
        }return result;
    }
    public List<ObjectNode> nodes(CurrentUser user,String box){
        access.require(user,box,PermissionPolicy.Action.READ_DIAGNOSTICS);List<ObjectNode> result=new ArrayList<>();
        for(var row:db.rows("SELECT n.*,r.online,r.last_seen FROM nodes n LEFT JOIN node_runtime r ON n.node_id=r.node_id WHERE n.box_id=? ORDER BY n.node_id",box)){
            ObjectNode item=JsonNodeFactory.instance.objectNode();item.put("node_id",row.get("node_id").toString());item.put("role",row.get("role").toString());item.put("caps",((Number)row.get("caps")).intValue());item.put("required",Boolean.TRUE.equals(row.get("required")));item.put("online",online(row));
            putNumber(item,"boot_id",row.get("current_boot_id"));putNumber(item,"last_received_at",row.get("last_received_at"));result.add(item);
        }return result;
    }
    static void putNumber(ObjectNode node,String key,Object value){if(value==null)node.putNull(key);else node.put(key,((Number)value).longValue());}
    public static String freshness(boolean known,BigInteger age,long limit){return !known?"UNKNOWN":age.signum()<0||age.compareTo(BigInteger.valueOf(limit))>=0?"STALE":"FRESH";}
    private ObjectNode sample(Map<String,Object> latest,Map<String,Object> runtime,String group){
        ObjectNode out=JsonNodeFactory.instance.objectNode();out.putNull("value");out.put("valid",false);out.putNull("observed_at");out.putNull("received_at");out.put("stale",true);out.putNull("src");out.put("time_quality","UNKNOWN");out.put("freshness","UNKNOWN");
        if(latest==null)return out;
        JsonNode payload=json.json(latest.get("payload")),value=payload.path(group);if(!value.isObject())return out;
        JsonNode time=group.equals("loc")?value.path("observation"):payload;
        ObjectNode safe=JsonNodeFactory.instance.objectNode();
        List<String> fields=group.equals("env")?List.of("temp_c","humi_pct","lux","valid","light_on","fan_on"):group.equals("pose")?List.of("acc_g","tilt_deg","state","valid"):List.of("lat","lon","valid","src");
        for(String field:fields)if(value.has(field))safe.set(field,value.get(field));
        out.set("value",safe);out.put("valid",value.path("valid").asBoolean());out.put("src",group.equals("loc")?"MOCK":group.equals("env")?"ENV":"POSE");
        putNumber(out,"received_at",latest.get("received_at"));
        if(time.isObject()){
            out.set("observed_at",time.path("ts").isMissingNode()?NullNode.instance:time.path("ts"));out.put("time_quality",time.path("ts_src").asText("UNKNOWN"));
            boolean same=runtime!=null&&((Number)latest.get("boot_id")).longValue()==((Number)runtime.get("boot_id")).longValue();
            BigInteger age=BigInteger.ZERO;
            if(same)age=new BigDecimal(runtime.get("uptime_ms").toString()).toBigInteger().subtract(time.path("uptime_ms").bigIntegerValue()).add(BigInteger.valueOf(Math.max(0,clock.millis()-((Number)runtime.get("last_seen")).longValue())));
            if(same){
                long first=((Number)latest.get("received_at")).longValue();
                if(group.equals("loc")){
                    var observation=db.one("SELECT received_at FROM location_samples WHERE node_id=? AND boot_id=? AND sample_seq=?",latest.get("node_id"),latest.get("boot_id"),time.path("sample_seq").asLong());
                    if(observation!=null)first=((Number)observation.get("received_at")).longValue();
                }
                age=age.max(BigInteger.valueOf(Math.max(0,clock.millis()-first)));
            }
            String fresh=freshness(same,age,group.equals("loc")?15000:5000);out.put("freshness",fresh);out.put("stale",!fresh.equals("FRESH"));
        }
        return out;
    }
    public ObjectNode snapshot(CurrentUser user,String box){
        access.require(user,box,PermissionPolicy.Action.READ_BUSINESS);
        ObjectNode out=JsonNodeFactory.instance.objectNode();out.put("box_id",box);
        Map<String,Map<String,Object>> runtime=new HashMap<>(),latest=new HashMap<>();
        int mask=0,required=0,present=0;List<JsonNode> sentries=new ArrayList<>();
        for(var row:db.rows("SELECT n.*,r.online,r.last_seen,r.boot_id,r.uptime_ms,r.heartbeat,r.actual_state FROM nodes n LEFT JOIN node_runtime r ON n.node_id=r.node_id WHERE n.box_id=?",box)){
            String role=row.get("role").toString();if(row.get("boot_id")!=null)runtime.put(role,row);
            if(online(row))mask|=role.equals("ENV")?1:2;
            if(Boolean.TRUE.equals(row.get("required"))){
                required++;if(online(row))present++;
                JsonNode state=row.get("actual_state")==null?NullNode.instance:json.json(row.get("actual_state"));
                JsonNode heartbeat=row.get("heartbeat")==null?NullNode.instance:json.json(row.get("heartbeat"));
                // Heartbeats are current observations, command acknowledgements establish state before the next heartbeat.
                if(state.has("sentry"))sentries.add(state);
                else if(heartbeat.has("sentry"))sentries.add(heartbeat.path("sentry"));
            }
        }
        out.put("online_mask",mask);out.put("connectivity",required==0?"UNKNOWN":present==required?"ONLINE":present==0?"OFFLINE":"PARTIAL");
        ObjectNode alarm=out.putObject("alarm");alarm.put("assessment","UNKNOWN");alarm.putNull("active");alarm.putNull("level");
        ObjectNode door=out.putObject("door");door.put("state","UNKNOWN");door.put("confidence","INSUFFICIENT");door.putNull("conclusion_id");door.putNull("observed_at");door.put("historical",false);
        for(var row:db.rows("SELECT l.*,n.role FROM node_latest l JOIN nodes n ON l.node_id=n.node_id WHERE l.box_id=?",box))latest.put(row.get("role").toString(),row);
        out.set("env",sample(latest.get("ENV"),runtime.get("ENV"),"env"));out.set("loc",sample(latest.get("POSE"),runtime.get("POSE"),"loc"));
        if(!user.role().equals("DELIVERY_USER")){
            out.set("pose",sample(latest.get("POSE"),runtime.get("POSE"),"pose"));
            ObjectNode sentry=out.putObject("sentry");var revision=new BigDecimal(db.one("SELECT sentry_revision FROM boxes WHERE box_id=?",box).get("sentry_revision").toString()).toBigInteger();sentry.put("revision",revision);
            String state="UNKNOWN";
            if(required>0&&sentries.size()==required){
                List<String> states=sentries.stream().map(s->s.path("sentry").asText(s.path("state").asText())).toList();
                boolean current=sentries.stream().allMatch(s->s.path("sentry_revision").bigIntegerValue().equals(revision)||s.path("revision").bigIntegerValue().equals(revision));
                state=current&&states.stream().allMatch("ARMED"::equals)&&present==required?"ARMED":states.stream().allMatch("DISARMED"::equals)?"DISARMED":states.contains("ARMING")?"ARMING":states.contains("ARMED")?"PARTIAL":"DEGRADED";
            }sentry.put("state",state);
        }
        return out;
    }
    public record Window(long from,long to,int page,int size,String type,String level){}
    public Window window(Map<String,String> query){
        try{
            long now=clock.millis(),to=query.containsKey("to")?Long.parseLong(query.get("to")):now;
            long from=query.containsKey("from")?Long.parseLong(query.get("from")):Math.max(0,to-86400000);
            int page=Integer.parseInt(query.getOrDefault("page","1")),size=Integer.parseInt(query.getOrDefault("size","20"));
            if(from<0||to<=from||to-from>31L*86400000||page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw ApiException.invalid();
            String level=query.get("level");if(level!=null&&!Set.of("INFO","WARNING","CRITICAL").contains(level))throw ApiException.invalid();
            String type=query.get("type");if(type!=null&&!type.matches("[A-Z_]{1,32}"))throw ApiException.invalid();
            return new Window(from,to,page,size,type,level);
        }catch(NumberFormatException e){throw ApiException.invalid();}
    }
    public ObjectNode history(CurrentUser user,String box,Map<String,String> query,boolean track){
        access.require(user,box,PermissionPolicy.Action.READ_BUSINESS);Window w=window(query);
        String table=track?"location_samples":"event_views",id=track?"id":"event_id";
        String where=" WHERE box_id=? AND received_at>=? AND received_at<?";List<Object> args=new ArrayList<>(List.of(box,w.from,w.to));
        if(!track){
            if(user.role().equals("DELIVERY_USER"))where+=" AND event_type IN ('DOOR_CONCLUSION','ALARM')";
            if(w.type!=null){where+=" AND event_type=?";args.add(w.type);}if(w.level!=null){where+=" AND level=?";args.add(w.level);}
        }
        long count=db.count("SELECT COUNT(*) FROM "+table+where,args.toArray());
        List<Object> paged=new ArrayList<>(args);paged.add(w.size);paged.add((w.page-1)*w.size);
        ObjectNode result=JsonNodeFactory.instance.objectNode();result.put("page",w.page);result.put("size",w.size);result.put("total",count);ArrayNode items=result.putArray("items");
        for(var row:db.rows("SELECT * FROM "+table+where+" ORDER BY received_at,"+id+" LIMIT ? OFFSET ?",paged.toArray())){
            if(!track)items.add(json.json(row.get("payload")));
            else{
                JsonNode loc=json.json(row.get("payload"));ObjectNode item=items.addObject();item.put("id","loc-"+row.get("id"));item.put("box_id",box);putNumber(item,"observed_at",row.get("device_ts"));putNumber(item,"received_at",row.get("received_at"));item.put("time_quality",row.get("ts_src").toString());item.set("lat",loc.get("lat"));item.set("lon",loc.get("lon"));item.set("valid",loc.get("valid"));item.put("src","MOCK");
            }
        }return result;
    }
    public static String csvCell(String value){if(value==null)return "\"\"";String trimmed=value.stripLeading();String safe=!trimmed.isEmpty()&&"=+-@".indexOf(trimmed.charAt(0))>=0?"'"+value:value;return "\""+safe.replace("\"","\"\"")+"\"";}
    public String export(CurrentUser user,String box,Map<String,String> query){
        access.require(user,box,PermissionPolicy.Action.EXPORT);Window w=window(query);
        StringBuilder csv=new StringBuilder("id,box_id,type,level,observed_at,received_at,time_quality,data\r\n");
        Map<String,String> page=new HashMap<>(query);page.put("size","100");int number=1;long total;
        do{
            page.put("page",Integer.toString(number++));JsonNode response=history(user,box,page,false);total=response.path("total").asLong();
            for(JsonNode item:response.path("items")){
                List<String> cells=new ArrayList<>();for(String key:List.of("id","box_id","type","level","observed_at","received_at","time_quality","data"))cells.add(csvCell(item.path(key).isContainerNode()?json.text(item.path(key)):item.path(key).isNull()?"":item.path(key).asText()));
                csv.append(String.join(",",cells)).append("\r\n");
            }
        }while((long)(number-1)*100<total);
        audit.record(user.id(),null,box,"EXPORT","COMPLETED");return csv.toString();
    }
}
