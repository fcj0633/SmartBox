package cn.smartbox.audit;
import java.time.Clock;
import cn.smartbox.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessException;
import org.slf4j.LoggerFactory;
@Service
public class AuditService {
    private final JdbcTemplate db;private final Clock clock;private final TransactionTemplate transaction;
    public AuditService(JdbcTemplate db,Clock clock,PlatformTransactionManager manager){
        this.db=db;this.clock=clock;this.transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void record(Long actor,String username,String target,String action,String result) {
        try {
            transaction.executeWithoutResult(s->db.update("INSERT INTO audit_logs(actor_user_id,attempted_username,target_box_id,action,result,request_id,occurred_at) VALUES(?,?,?,?,?,?,?)",
                actor,Inputs.length(username,1,128)?username:null,Inputs.id(target)?target:null,action,result,ApiEnvelope.requestId(),clock.millis()));
        }catch(DataAccessException|TransactionException unavailable){LoggerFactory.getLogger(AuditService.class).warn("Audit database write unavailable request_id={} action={}",ApiEnvelope.requestId(),action);}
    }
}
