package cn.smartbox.live;

import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable domain-event processing for door evidence and supported alarm rules. */
@Service
public class DomainEventWorker {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DomainEventWorker.class);
    private final LiveStore db;
    private final ContractValidator json;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Environment env;

    public DomainEventWorker(LiveStore db, ContractValidator json, Clock clock,
                             PlatformTransactionManager manager, Environment env) {
        this.db=db; this.json=json; this.clock=clock;
        this.tx=new TransactionTemplate(manager); this.env=env;
    }

    private Map<String,Object> claim(String owner,long now) {
        return tx.execute(status -> {
            var row=db.one("SELECT * FROM processing_outbox WHERE task_type='DOMAIN_EVENT' AND " +
                "((status IN ('PENDING','RETRY') AND next_attempt_at<=?) OR (status='PROCESSING' AND locked_until<=?)) " +
                "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",now,now);
            if(row!=null)db.update("UPDATE processing_outbox SET status='PROCESSING',lock_owner=?,locked_until=?,attempts=attempts+1,updated_at=? WHERE id=?",
                owner,now+10000,now,row.get("id"));
            return row;
        });
    }

    private boolean process(Map<String,Object> work,String owner) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            long now=clock.millis();
            var locked=db.one("SELECT * FROM processing_outbox WHERE id=? FOR UPDATE",work.get("id"));
            if(locked==null||!owner.equals(locked.get("lock_owner"))||((Number)locked.get("locked_until")).longValue()<=now)return false;
            var event=db.one("SELECT * FROM events WHERE id=?",locked.get("event_id"));
            if(event==null)throw new IllegalStateException("domain event missing");
            String type=event.get("type").toString();
            if(type.equals("DOOR_EVIDENCE")) {
                // Serialize conclusions per box, including two concurrent evidence workers.
                db.one("SELECT box_id FROM boxes WHERE box_id=? FOR UPDATE",event.get("box_id"));
                if(deferOrConclude(event,now)) {
                    db.update("UPDATE processing_outbox SET status='RETRY',next_attempt_at=?,lock_owner=NULL,locked_until=NULL,updated_at=? WHERE id=? AND lock_owner=?",
                        Math.max(now+50,((Number)event.get("received_at")).longValue()+2000),now,locked.get("id"),owner);
                    return true;
                }
            }
            if(Boolean.TRUE.equals(event.get("replayed"))||Objects.equals(event.get("replayed"),1)) {
                db.update("UPDATE processing_outbox SET status='DONE',lock_owner=NULL,locked_until=NULL,updated_at=? WHERE id=? AND lock_owner=?",now,locked.get("id"),owner);
                return true;
            }
            if(Set.of("ENV_HIGH","ENV_LOW","ENV_RECOVERED","SENTRY_INTRUSION","GEOFENCE_EXIT","GEOFENCE_ENTER").contains(type)) {
                updateAlarm(event,type,now);
                refreshAlarmState(event.get("box_id").toString(),now);
            }
            db.update("UPDATE processing_outbox SET status='DONE',lock_owner=NULL,locked_until=NULL,updated_at=? WHERE id=? AND lock_owner=?",now,locked.get("id"),owner);
            return true;
        }));
    }

    /** @return true while the first door item is waiting for its two-second pairing window. */
    private boolean deferOrConclude(Map<String,Object> event,long now) {
        long id=((Number)event.get("id")).longValue();String box=event.get("box_id").toString();
        var data=json.json(event.get("data"));String action=data.path("action").asText();
        String kind=data.has("tilt_deg")&&!data.has("lux")?"TILT":"LIGHT";
        var evidence=db.one("SELECT * FROM event_evidence WHERE event_id=?",id);
        if(evidence==null) {
            Object observed=event.get("device_ts"),uncertainty=event.get("time_uncertainty_ms");
            db.update("INSERT INTO event_evidence(event_id,box_id,node_id,kind,action,observed_at,ts_src,time_uncertainty_ms,received_at,replayed,data,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,box,event.get("node_id"),kind,action,observed,event.get("ts_src"),uncertainty,event.get("received_at"),event.get("replayed"),json.text(data),now);
            evidence=db.one("SELECT * FROM event_evidence WHERE event_id=?",id);
        }
        // After a restart both raw events can predate their domain-work execution.
        // Register the complementary committed input before finalizing a single source.
        for(var other:db.rows("SELECT * FROM events WHERE box_id=? AND node_id<>? AND type='DOOR_EVIDENCE' AND src=? AND replayed=0 AND ABS(received_at-?)<=2000 ORDER BY received_at,id LIMIT 32",
                box,event.get("node_id"),kind.equals("LIGHT")?"TILT":"LIGHT",event.get("received_at"))){
            if(db.one("SELECT event_id FROM event_evidence WHERE event_id=?",other.get("id"))!=null)continue;
            JsonNode raw=json.json(other.get("data"));
            db.update("INSERT INTO event_evidence(event_id,box_id,node_id,kind,action,observed_at,ts_src,time_uncertainty_ms,received_at,replayed,data,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                other.get("id"),box,other.get("node_id"),kind.equals("LIGHT")?"TILT":"LIGHT",raw.path("action").asText(),other.get("device_ts"),other.get("ts_src"),other.get("time_uncertainty_ms"),other.get("received_at"),other.get("replayed"),json.text(raw),now);
        }
        if(Boolean.TRUE.equals(evidence.get("replayed"))) {
            createConclusion(List.of(evidence),"UNKNOWN","INSUFFICIENT","HISTORICAL",now,true,true);
            return false;
        }
        if(evidence.get("conclusion_id")!=null)return false;
        long received=((Number)evidence.get("received_at")).longValue();
        var partner=db.one("SELECT * FROM event_evidence WHERE box_id=? AND node_id<>? AND kind<>? AND conclusion_id IS NULL AND replayed=0 AND ABS(received_at-?)<=2000 AND (ts_src<>'SNTP' OR ?<>'SNTP' OR ABS(observed_at-?)<=2000) ORDER BY received_at,event_id LIMIT 1 FOR UPDATE",
            box,evidence.get("node_id"),evidence.get("kind"),received,evidence.get("ts_src"),evidence.get("observed_at"));
        if(partner!=null) {
            boolean timesComparable=Objects.equals(evidence.get("ts_src"),"SNTP")&&Objects.equals(partner.get("ts_src"),"SNTP");
            if(timesComparable&&Math.abs(((Number)evidence.get("observed_at")).longValue()-((Number)partner.get("observed_at")).longValue())>2000)partner=null;
        }
        long windowStart=partner==null?received:Math.min(received,((Number)partner.get("received_at")).longValue());
        if(now<windowStart+2000)return true;
        List<Map<String,Object>> items=partner==null?List.of(evidence):List.of(evidence,partner);
        String state="UNKNOWN",confidence="INSUFFICIENT",reason="SINGLE_SOURCE_CLOSE";
        boolean historical=false,unfused=partner==null;
        if(partner!=null) {
            String otherAction=partner.get("action").toString();
            if(!action.equals(otherAction))reason="CONFLICTING";
            else {
                boolean highQuality=pairQuality(evidence,partner,now);
                state=action.equals("OPEN")?"OPEN":"CLOSED";
                confidence=highQuality?"HIGH":"MEDIUM";
                reason="DUAL_SOURCE";
            }

        } else if(action.equals("OPEN")&&kind.equals("TILT")&&data.path("tilt_deg").asDouble()>60.0) {
            state="OPEN";confidence="MEDIUM";reason="TILT_STRONG";

        } else {
            reason=action.equals("OPEN")?kind.equals("LIGHT")?"LIGHT_ONLY":"TILT_WEAK":"SINGLE_SOURCE_CLOSE";

        }
        String conclusion=conclusionId(id,partner==null?id:((Number)partner.get("event_id")).longValue());
        createConclusion(items,state,confidence,reason,now,historical,unfused,conclusion);
        return false;
    }

    private boolean pairQuality(Map<String,Object> first,Map<String,Object> second,long now) {
        if(!Objects.equals(first.get("ts_src"),"SNTP")||!Objects.equals(second.get("ts_src"),"SNTP"))return false;
        if(first.get("observed_at")==null||second.get("observed_at")==null||first.get("time_uncertainty_ms")==null||second.get("time_uncertainty_ms")==null)return false;
        if(((Number)first.get("time_uncertainty_ms")).longValue()>500||((Number)second.get("time_uncertainty_ms")).longValue()>500)return false;
        for(Object node:List.of(first.get("node_id"),second.get("node_id"))) {
            var runtime=db.one("SELECT online,last_seen FROM node_runtime WHERE node_id=?",node);
            if(runtime==null||!isOnline(runtime,now))return false;
        }
        return true;
    }

    private boolean isOnline(Map<String,Object> row,long now) {
        return (Boolean.TRUE.equals(row.get("online"))||Objects.equals(row.get("online"),1))&&now-((Number)row.get("last_seen")).longValue()<90000;
    }

    private String conclusionId(long first,long second) { return "door-"+Math.min(first,second); }

    private void createConclusion(List<Map<String,Object>> evidence,String state,String confidence,String reason,
                                  long now,boolean historical,boolean unfused) {
        createConclusion(evidence,state,confidence,reason,now,historical,unfused,
            conclusionId(((Number)evidence.get(0).get("event_id")).longValue(),((Number)evidence.get(evidence.size()-1).get("event_id")).longValue()));
    }

    private void createConclusion(List<Map<String,Object>> evidence,String state,String confidence,String reason,
                                  long now,boolean historical,boolean unfused,String conclusion) {
        Map<String,Object> first=evidence.get(0);String box=first.get("box_id").toString();
        if(!historical&&!state.equals("UNKNOWN")) {
            var previous=db.one("SELECT d.conclusion_id,d.source_event_ids FROM box_domain_state s JOIN door_conclusions d ON d.conclusion_id=s.door_conclusion_id WHERE s.box_id=? AND s.door_state=? AND s.door_confidence=? FOR UPDATE",box,state,confidence);
            if(previous!=null) {
                LinkedHashSet<Long> ids=new LinkedHashSet<>();
                for(JsonNode old:json.json(previous.get("source_event_ids")))ids.add(old.asLong());
                for(var item:evidence){ids.add(((Number)item.get("event_id")).longValue());db.update("UPDATE event_evidence SET conclusion_id=? WHERE event_id=? AND conclusion_id IS NULL",previous.get("conclusion_id"),item.get("event_id"));}
                ArrayNode linked=JsonNodeFactory.instance.arrayNode();ids.forEach(linked::add);
                db.update("UPDATE door_conclusions SET source_event_ids=? WHERE conclusion_id=?",json.text(linked),previous.get("conclusion_id"));
                return;
            }
        }
        Long observed=null;boolean allSynced=evidence.stream().allMatch(e->Objects.equals(e.get("ts_src"),"SNTP")&&e.get("observed_at")!=null);
        if(allSynced)observed=evidence.stream().mapToLong(e->((Number)e.get("observed_at")).longValue()).max().orElseThrow();
        ArrayNode sources=JsonNodeFactory.instance.arrayNode();for(Map<String,Object> e:evidence)sources.add(((Number)e.get("event_id")).longValue());
        db.update("INSERT INTO door_conclusions(conclusion_id,box_id,state,confidence,reason,observed_at,received_at,historical,unfused,source_event_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE conclusion_id=conclusion_id",
            conclusion,box,state,confidence,reason,observed,now,historical,unfused,json.text(sources),now);
        for(Map<String,Object> item:evidence)db.update("UPDATE event_evidence SET conclusion_id=? WHERE event_id=? AND conclusion_id IS NULL",conclusion,item.get("event_id"));
        ObjectNode data=JsonNodeFactory.instance.objectNode();data.put("state",state);data.put("confidence",confidence);data.put("reason",reason);data.put("conclusion_id",conclusion);
        ObjectNode view=JsonNodeFactory.instance.objectNode();view.put("id",conclusion);view.put("box_id",box);view.put("type","DOOR_CONCLUSION");view.put("level","INFO");putNullable(view,"observed_at",observed);view.put("received_at",now);view.put("time_quality",observed==null?"UNSYNCED":"SNTP");view.put("historical",historical);view.set("data",data);
        db.update("INSERT INTO box_domain_views(view_id,box_id,received_at,event_type,level,payload,created_at) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE view_id=view_id",
            conclusion,box,now,"DOOR_CONCLUSION","INFO",json.text(view),now);
        if(!historical) {
            var current=db.one("SELECT door_received_at FROM box_domain_state WHERE box_id=? FOR UPDATE",box);
            long old=current==null?0:((Number)current.get("door_received_at")).longValue();
            if(now>=old)db.update("INSERT INTO box_domain_state(box_id,door_state,door_confidence,door_conclusion_id,door_observed_at,door_historical,door_received_at,updated_at) VALUES(?,?,?,?,?,0,?,?) ON DUPLICATE KEY UPDATE door_state=VALUES(door_state),door_confidence=VALUES(door_confidence),door_conclusion_id=VALUES(door_conclusion_id),door_observed_at=VALUES(door_observed_at),door_historical=0,door_received_at=VALUES(door_received_at),updated_at=VALUES(updated_at)",
                box,state,confidence,conclusion,observed,now,now);
        }
    }

    private void updateAlarm(Map<String,Object> event,String type,long now) {
        String box=event.get("box_id").toString();long id=((Number)event.get("id")).longValue();
        String key=null,level=event.get("level").toString();boolean start=false;List<String> recover=new ArrayList<>();
        JsonNode data=json.json(event.get("data"));
        if(type.equals("ENV_HIGH")||type.equals("ENV_LOW")) {
            String metric=data.path("metric").asText();if(!Set.of("temp_c","humi_pct").contains(metric))return;
            key="env."+(metric.equals("temp_c")?"temp":"humi")+(type.equals("ENV_HIGH")?".high":".low");start=true;level="WARNING";
        } else if(type.equals("ENV_RECOVERED")) {
            String metric=data.path("metric").asText();if(!Set.of("temp_c","humi_pct").contains(metric))return;
            String prefix="env."+(metric.equals("temp_c")?"temp":"humi");recover.add(prefix+".high");recover.add(prefix+".low");
        } else if(type.equals("GEOFENCE_EXIT")){key="geofence.exit";start=true;level="WARNING";}
        else if(type.equals("GEOFENCE_ENTER"))recover.add("geofence.exit");
        else if(type.equals("SENTRY_INTRUSION")){key="sentry.intrusion";start=true;level="CRITICAL";}
        else if(!Set.of("DOOR_EVIDENCE","POSE_CHANGED","IMPACT").contains(type))return;

        boolean changed=false;
        if(start) {
            var prior=db.one("SELECT active FROM alarms WHERE box_id=? AND alarm_key=? FOR UPDATE",box,key);
            changed=prior==null||!(Boolean.TRUE.equals(prior.get("active"))||Objects.equals(prior.get("active"),1));
            db.update("INSERT INTO alarms(box_id,alarm_key,active,level,started_at,updated_at,source_event_id,recovered_by_event_id,details) VALUES(?,?,1,?,?,?,?,NULL,?) ON DUPLICATE KEY UPDATE active=1,level=VALUES(level),started_at=VALUES(started_at),updated_at=VALUES(updated_at),source_event_id=VALUES(source_event_id),recovered_by_event_id=NULL,details=VALUES(details)",
                box,key,level,now,now,id,json.text(data));
        }
        for(String candidate:recover) {
            var row=db.one("SELECT active FROM alarms WHERE box_id=? AND alarm_key=? FOR UPDATE",box,candidate);
            if(row!=null&&(Boolean.TRUE.equals(row.get("active"))||Objects.equals(row.get("active"),1))) {
                db.update("UPDATE alarms SET active=0,updated_at=?,recovered_by_event_id=?,details=? WHERE box_id=? AND alarm_key=?",now,id,json.text(data),box,candidate);changed=true;
            }
        }
        if(!changed)return;
        var highest=db.one("SELECT level FROM alarms WHERE box_id=? AND active=1 ORDER BY CASE level WHEN 'CRITICAL' THEN 3 WHEN 'WARNING' THEN 2 ELSE 1 END DESC LIMIT 1",box);
        Boolean active=highest==null?Boolean.FALSE:Boolean.TRUE;String activeLevel=highest==null?null:highest.get("level").toString();
        db.update("INSERT INTO alarm_state(box_id,assessment,active,level,updated_at) VALUES(?,'EVALUATED',?,?,?) ON DUPLICATE KEY UPDATE assessment='EVALUATED',active=VALUES(active),level=VALUES(level),updated_at=VALUES(updated_at)",box,active,activeLevel,now);
        ObjectNode alarm=JsonNodeFactory.instance.objectNode();alarm.put("assessment","EVALUATED");alarm.put("active",active);if(activeLevel==null)alarm.putNull("level");else alarm.put("level",activeLevel);
        ObjectNode view=JsonNodeFactory.instance.objectNode();String viewId="alarm-"+id;view.put("id",viewId);view.put("box_id",box);view.put("type","ALARM");view.put("level",start?level:"INFO");putNullable(view,"observed_at",event.get("device_ts") instanceof Number n?n.longValue():null);view.put("received_at",now);view.put("time_quality",event.get("ts_src").toString());view.put("historical",Boolean.TRUE.equals(event.get("replayed")));view.set("data",alarm);
        db.update("INSERT INTO box_domain_views(view_id,box_id,received_at,event_type,level,payload,created_at) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE view_id=view_id",
            viewId,box,now,"ALARM",start?level:"INFO",json.text(view),now);
    }

    private void refreshAlarmState(String box,long now) {
        var highest=db.one("SELECT level FROM alarms WHERE box_id=? AND active=1 ORDER BY CASE level WHEN 'CRITICAL' THEN 3 WHEN 'WARNING' THEN 2 ELSE 1 END DESC LIMIT 1",box);
        Boolean active=highest==null?Boolean.FALSE:Boolean.TRUE;String level=highest==null?null:highest.get("level").toString();
        db.update("INSERT INTO alarm_state(box_id,assessment,active,level,updated_at) VALUES(?,'EVALUATED',?,?,?) ON DUPLICATE KEY UPDATE assessment='EVALUATED',active=VALUES(active),level=VALUES(level),updated_at=VALUES(updated_at)",box,active,level,now);
    }

    private static void putNullable(ObjectNode node,String key,Long value) { if(value==null)node.putNull(key);else node.put(key,value); }

    @Scheduled(fixedDelay=100)
    public void run() {
        if(env.getProperty("smartbox.workers.enabled",Boolean.class,true))runBatch();
    }

    /** Runs one bounded batch; also called directly by isolated database integration tests. */
    public int runBatch() {
        int processed=0;
        for(int i=0;i<32;i++) {
            String owner=UUID.randomUUID().toString();Map<String,Object> work=null;
            try { work=claim(owner,clock.millis());if(work==null)return processed;if(process(work,owner))processed++; }
            catch(Exception error) {
                log.warn("Domain event work failed for outbox id {} ({})",work==null?"unclaimed":work.get("id"),error.getClass().getSimpleName());
                if(work!=null)try{long now=clock.millis();db.update("UPDATE processing_outbox SET status='RETRY',last_error_code='DOMAIN_PROCESSING_FAILED',next_attempt_at=?,lock_owner=NULL,locked_until=NULL,updated_at=? WHERE id=? AND lock_owner=?",now+1000,now,work.get("id"),owner);}catch(Exception ignored){}
                return processed;
            }
        }
        return processed;
    }
}
