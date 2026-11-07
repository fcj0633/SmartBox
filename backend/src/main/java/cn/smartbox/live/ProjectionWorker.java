package cn.smartbox.live;

import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProjectionWorker {
    private final LiveStore db;private final ContractValidator json;private final Clock clock;private final TransactionTemplate tx;private final org.springframework.core.env.Environment env;
    public ProjectionWorker(LiveStore db,ContractValidator json,Clock clock,PlatformTransactionManager manager,org.springframework.core.env.Environment env){this.db=db;this.json=json;this.clock=clock;this.tx=new TransactionTemplate(manager);this.env=env;}
    public Map<String,Object> claim(String owner,long now){return tx.execute(s->{
        var row=db.one("SELECT * FROM processing_outbox WHERE task_type='EVENT_VIEW' AND (status IN ('PENDING','RETRY') AND next_attempt_at<=? OR status='PROCESSING' AND locked_until<=?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",now,now);
        if(row!=null)db.update("UPDATE processing_outbox SET status='PROCESSING',lock_owner=?,locked_until=?,attempts=attempts+1,updated_at=? WHERE id=?",owner,now+10000,now,row.get("id"));return row;
    });}
    public boolean complete(Map<String,Object> work,String owner){return Boolean.TRUE.equals(tx.execute(s->{
        var locked=db.one("SELECT * FROM processing_outbox WHERE id=? FOR UPDATE",work.get("id"));long now=clock.millis();
        if(locked==null||!owner.equals(locked.get("lock_owner"))||((Number)locked.get("locked_until")).longValue()<=now)return false;
        var event=db.one("SELECT * FROM events WHERE id=?",locked.get("event_id"));
        ObjectNode view=JsonNodeFactory.instance.objectNode();view.put("id","event-"+event.get("id"));view.put("box_id",event.get("box_id").toString());view.put("type",event.get("type").toString());view.put("level",event.get("level").toString());
        QueryService.putNumber(view,"observed_at",event.get("device_ts"));QueryService.putNumber(view,"received_at",event.get("received_at"));view.put("time_quality",event.get("ts_src").toString());view.put("historical",Boolean.TRUE.equals(event.get("replayed")));view.set("data",json.eventData(event.get("type").toString(),json.json(event.get("data"))));
        db.update("INSERT INTO event_views(event_id,box_id,received_at,event_type,level,payload) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id=event_id",event.get("id"),event.get("box_id"),event.get("received_at"),event.get("type"),event.get("level"),json.text(view));
        db.update("UPDATE processing_outbox SET status='DONE',lock_owner=NULL,locked_until=NULL,updated_at=? WHERE id=? AND lock_owner=?",now,locked.get("id"),owner);return true;
    }));}
    @Scheduled(fixedDelay=100)
    public void run(){if(!env.getProperty("smartbox.workers.enabled",Boolean.class,true))return;for(int i=0;i<16;i++){
        String owner=UUID.randomUUID().toString();Map<String,Object> work=null;
        try{work=claim(owner,clock.millis());if(work==null)return;complete(work,owner);}
        catch(Exception error){if(work!=null)try{db.update("UPDATE processing_outbox SET status='RETRY',last_error_code='PROCESSING_FAILED',next_attempt_at=?,lock_owner=NULL,locked_until=NULL WHERE id=? AND lock_owner=?",clock.millis()+Math.min(30000,1000L*(1+((Number)work.get("attempts")).intValue())),work.get("id"),owner);}catch(Exception ignored){}return;}
    }}
}
