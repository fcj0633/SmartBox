package cn.smartbox.live;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/** Explicit test-only reset. Never available in the dev profile or a web process. */
@Component
@Profile("test")
@ConditionalOnProperty(name="smartbox.integration.reset",havingValue="true")
public class IntegrationReset implements ApplicationRunner {
    private final LiveStore db;private final Environment env;private final TransactionTemplate tx;
    public IntegrationReset(LiveStore db,Environment env,PlatformTransactionManager manager){this.db=db;this.env=env;this.tx=new TransactionTemplate(manager);}
    @Override public void run(ApplicationArguments args){
        if(!"true".equals(env.getProperty("SMARTBOX_TEST_ALLOW_RESET"))||!"none".equals(env.getProperty("spring.main.web-application-type"))||!"smartbox_test".equals(db.one("SELECT DATABASE() AS name").get("name"))||!db.one("SELECT CURRENT_USER() AS name").get("name").toString().startsWith("smartbox_test_app@"))throw new IllegalStateException("Test reset rejected");
        tx.executeWithoutResult(s->{
            for(String table:List.of("command_receipts","command_dispatch","idempotency_records","node_commands","command_operations","event_views","processing_outbox","events","location_samples","node_latest","telemetry","node_runtime"))db.update("DELETE FROM "+table);
            db.update("UPDATE nodes SET current_boot_id=NULL,last_received_at=NULL");db.update("UPDATE boxes SET sentry_revision=0,profile_config=NULL");
        });
    }
}
