package cn.smartbox;
import cn.smartbox.auth.*;
import cn.smartbox.live.*;
import cn.smartbox.audit.AuditService;
import cn.smartbox.common.*;
import cn.smartbox.config.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.DataAccessException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="smartbox.workers.enabled=false")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveIT {
    @Autowired JdbcTemplate db;
    @Autowired BootstrapService bootstrap;
    @Autowired ObjectMapper json;
    @Autowired AuthMapper users;
    @Autowired TokenService tokens;
    @Autowired LocalSettings settings;
    @Autowired PlatformTransactionManager manager;
    @Autowired AuditService audit;
    @Autowired Flyway flyway;
    @LocalServerPort int port;
    JsonNode fixture;
    final HttpClient client=HttpClient.newHttpClient();
    @BeforeAll void seed()throws Exception{
        TestDatabaseGuard.require(System.getenv("SPRING_PROFILES_ACTIVE"),System.getenv("SMARTBOX_DB_URL"),db.queryForObject("SELECT DATABASE()",String.class),db.queryForObject("SELECT CURRENT_USER()",String.class),System.getenv("SMARTBOX_TEST_ALLOW_RESET"));
        new TransactionTemplate(manager).executeWithoutResult(status->{
            for(String table:List.of("command_receipts","command_dispatch","event_views","location_samples","node_runtime","audit_logs","idempotency_records","node_commands","command_operations","processing_outbox","events","node_latest","telemetry","user_box_access","users","nodes","boxes"))db.update("DELETE FROM "+table);
        });
        fixture=json.readTree(Files.readString(Path.of(System.getenv("SMARTBOX_BOOTSTRAP_FILE"))));bootstrap.initialize(fixture);
    }
    JsonNode account(String role,int box){
        for(JsonNode u:fixture.get("users"))if(u.get("role").asText().equals(role)&&u.get("box_ids").get(0).asText().equals("BOX-000"+box))return u;
        throw new AssertionError("Missing local test account");
    }
    record Reply(int status,JsonNode body){}
    Reply request(String method,String path,String token,JsonNode body)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(body!=null)b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else b.method(method,HttpRequest.BodyPublishers.noBody());
        var r=client.send(b.build(),HttpResponse.BodyHandlers.ofString());return new Reply(r.statusCode(),json.readTree(r.body()));
    }
    String login(JsonNode u)throws Exception{
        ObjectNode input=json.createObjectNode();input.put("username",u.get("username").asText());input.put("password",u.get("password").asText());
        Reply r=request("POST","/api/v1/auth/login",null,input);assertEquals(200,r.status());return r.body().path("data").path("access_token").asText();
    }
    @Autowired IngestionService ingestion;
    @Autowired ContractValidator validator;
    @Autowired LiveStore store;
    @Autowired QueryService query;
    @Autowired CommandService commands;
    @Autowired ProjectionWorker worker;
    @BeforeEach void clearLive(){
        for(String table:List.of("command_receipts","command_dispatch","idempotency_records","node_commands","command_operations","event_views","processing_outbox","events","location_samples","node_latest","telemetry","node_runtime"))db.update("DELETE FROM "+table);
        db.update("UPDATE nodes SET current_boot_id=NULL,last_received_at=NULL");
        db.update("UPDATE boxes SET sentry_revision=0");
    }
    CurrentUser operator(){UserAccount a=users.byUsername(account("LOGISTICS_OPERATOR",1).path("username").asText());return new CurrentUser(a.id(),a.username(),a.roleCode());}
    ObjectNode input(String file)throws Exception{return (ObjectNode)json.readTree(Files.readString(Path.of("../contracts/mqtt/fixtures/positive/"+file)));}
    void receive(ObjectNode data,String suffix){ingestion.accept(new MqttGateway.Packet("box/BOX-0001/"+data.path("node_id").asText()+"/"+suffix,validator.text(data).getBytes(java.nio.charset.StandardCharsets.UTF_8),suffix.equals("property")?0:1,false));}
    ObjectNode event()throws Exception {
        ObjectNode data=input("mqtt-event.json");return data;
    }

    @Test void batchCommitDedupCanonicalConflictRollback()throws Exception{
        var data=event();receive(data,"event");receive(data,"event");
        assertEquals(1,store.count("SELECT COUNT(*) FROM events"));assertEquals(1,store.count("SELECT COUNT(*) FROM processing_outbox"));
        ((ObjectNode)data.path("events").get(0)).put("replayed",true);receive(data,"event");
        ((ObjectNode)data.path("events").get(0).path("data")).put("lux",999);
        ObjectNode newEvent=((ObjectNode)data.path("events").get(0)).deepCopy();newEvent.put("event_seq",2);((ArrayNode)data.path("events")).insert(0,newEvent);
        assertThrows(ApiException.class,()->receive(data,"event"));assertEquals(1,store.count("SELECT COUNT(*) FROM events"));
    }
    @Test void outboxLeaseOwnerAndDurableEffect()throws Exception{
        receive(event(),"event");var work=worker.claim("first",System.currentTimeMillis());assertNotNull(work);
        assertFalse(worker.complete(work,"wrong"));assertTrue(worker.complete(work,"first"));
        assertEquals(1,store.count("SELECT COUNT(*) FROM event_views"));assertNull(worker.claim("second",System.currentTimeMillis()));
        assertEquals("DONE",store.one("SELECT status FROM processing_outbox").get("status"));
    }
    @Test void abandonedLeaseCanBeReclaimed()throws Exception{
        receive(event(),"event");var first=worker.claim("first",System.currentTimeMillis());
        store.update("UPDATE processing_outbox SET locked_until=0");var second=worker.claim("second",System.currentTimeMillis());
        assertNotNull(second);assertFalse(worker.complete(first,"first"));assertTrue(worker.complete(second,"second"));
    }
    @Test void concurrentOutboxClaimsHaveExactlyOneOwner()throws Exception{
        receive(event(),"event");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            java.util.concurrent.Callable<java.util.Map.Entry<String,java.util.Map<String,Object>>> first=()->new java.util.AbstractMap.SimpleEntry<>("owner-a",worker.claim("owner-a",System.currentTimeMillis()));
            java.util.concurrent.Callable<java.util.Map.Entry<String,java.util.Map<String,Object>>> second=()->new java.util.AbstractMap.SimpleEntry<>("owner-b",worker.claim("owner-b",System.currentTimeMillis()));
            var results=pool.invokeAll(java.util.List.of(first,second));int winners=0;
            for(var result:results){var claim=result.get();if(claim.getValue()!=null){winners++;assertTrue(worker.complete(claim.getValue(),claim.getKey()));}}
            assertEquals(1,winners);assertEquals(1,store.count("SELECT COUNT(*) FROM event_views"));
        }finally{pool.shutdownNow();}
    }
    @Test void invalidIdentityAndRetainNeverSave()throws Exception{
        var data=event();data.put("box_id","BOX-0002");assertThrows(ApiException.class,()->receive(data,"event"));
        assertEquals(0,store.count("SELECT COUNT(*) FROM events"));
        var valid=event();assertThrows(ApiException.class,()->ingestion.accept(new MqttGateway.Packet("box/BOX-0001/BOX-0001-ENV/event",validator.text(valid).getBytes(),1,true)));
    }
    @Test void telemetryOrderBootAndLwt()throws Exception{
        var data=input("mqtt-property.json");data.put("property_seq",1);receive(data,"property");long boot=data.path("boot_id").asLong();
        ObjectNode next=data.deepCopy();next.put("property_seq",2);next.put("uptime_ms",31000);receive(next,"property");
        receive(data,"property");assertEquals(2L,((Number)store.one("SELECT property_seq FROM node_latest").get("property_seq")).longValue());
        ObjectNode old=data.deepCopy();old.put("boot_id",boot-1);old.put("property_seq",3);receive(old,"property");
        ObjectNode will=json.createObjectNode().put("node_id",data.path("node_id").asText()).put("boot_id",boot-1).put("online",false);receive(will,"availability");
        assertTrue((Boolean)store.one("SELECT online FROM node_runtime").get("online"));
        will.put("boot_id",boot);receive(will,"availability");assertFalse((Boolean)store.one("SELECT online FROM node_runtime").get("online"));
    }
    @Test void snapshotUnknownProjectionAndWindow(){
        var snap=query.snapshot(operator(),"BOX-0001");validator.validate("Snapshot",snap);assertTrue(snap.path("alarm").path("active").isNull());
        CurrentUser delivery=new CurrentUser(operator().id(),operator().username(),"DELIVERY_USER");var limited=query.snapshot(delivery,"BOX-0001");validator.validate("DeliverySnapshot",limited);assertFalse(limited.has("pose"));assertFalse(limited.has("sentry"));
        assertThrows(ApiException.class,()->query.window(Map.of("from","0","to","2678400001")));assertThrows(ApiException.class,()->query.window(Map.of("page","0")));
    }
    @Test void historyProjectionPermissionsAndCsv()throws Exception{
        receive(event(),"event");var work=worker.claim("projection",System.currentTimeMillis());worker.complete(work,"projection");
        assertEquals(1,query.history(operator(),"BOX-0001",Map.of(),false).path("total").asInt());
        var delivery=new CurrentUser(operator().id(),operator().username(),"DELIVERY_USER");assertEquals(0,query.history(delivery,"BOX-0001",Map.of(),false).path("total").asInt());
        assertTrue(query.export(operator(),"BOX-0001",Map.of()).contains("DOOR_EVIDENCE"));
    }
    @Test void persistentCommandIdempotencyConflictAndPermission()throws Exception{
        var property=input("mqtt-property.json");property.put("ts",System.currentTimeMillis());receive(property,"property");
        JsonNode command=json.readTree("{\"cmd\":\"output\",\"params\":{\"kind\":\"light\",\"on\":true,\"duration_s\":2}}");
        String id=commands.submit(operator(),"BOX-0001","live-key",command);assertEquals(id,commands.submit(operator(),"BOX-0001","live-key",command));
        ObjectNode changed=command.deepCopy();((ObjectNode)changed.path("params")).put("on",false);assertThrows(ApiException.class,()->commands.submit(operator(),"BOX-0001","live-key",changed));
        assertEquals(1,store.count("SELECT COUNT(*) FROM command_operations"));assertEquals(1,store.count("SELECT COUNT(*) FROM command_dispatch"));
        var viewer=new CurrentUser(operator().id(),operator().username(),"LOGISTICS_VIEWER");assertThrows(ApiException.class,()->commands.submit(viewer,"BOX-0001","another",command));
    }
    @Test void unsyncedNodeCannotReceiveControl()throws Exception{
        var property=input("mqtt-property.json");property.putNull("ts");property.putNull("time_uncertainty_ms");property.put("ts_src","UNSYNCED");receive(property,"property");
        JsonNode command=json.readTree("{\"cmd\":\"output\",\"params\":{\"kind\":\"light\",\"on\":true,\"duration_s\":2}}");
        assertThrows(ApiException.class,()->commands.submit(operator(),"BOX-0001","unsynced",command));assertEquals(0,store.count("SELECT COUNT(*) FROM command_dispatch"));
    }
    @Test void lateReceiptPreservesOperationAndUpdatesActualFact()throws Exception{
        var property=input("mqtt-property.json");property.put("ts",System.currentTimeMillis());receive(property,"property");
        JsonNode body=json.readTree("{\"cmd\":\"output\",\"params\":{\"kind\":\"light\",\"on\":true,\"duration_s\":2}}");
        String op=commands.submit(operator(),"BOX-0001","late",body);
        store.update("UPDATE command_operations SET deadline_at=0 WHERE operation_id=?",op);commands.refreshOutcomes();
        assertEquals("EXPIRED",commands.operation(operator(),"BOX-0001",op).path("status").asText());
        var row=store.one("SELECT * FROM node_commands WHERE operation_id=?",op);
        ObjectNode ack=json.createObjectNode().put("v",1).put("box_id","BOX-0001").put("node_id","BOX-0001-ENV").put("boot_id",12).put("ts",((Number)row.get("issued_at")).longValue()+100).put("ts_src","SNTP").put("uptime_ms",31000).put("time_uncertainty_ms",1).put("request_id",row.get("request_id").toString()).put("operation_id",op).put("result","ok").put("code","completed");
        ack.putObject("actual_state").put("kind","light").put("on",true);
        receive(ack,"command_ack");receive(ack,"command_ack");
        assertEquals("EXPIRED",commands.operation(operator(),"BOX-0001",op).path("status").asText());
        assertEquals("SUCCEEDED",commands.operation(operator(),"BOX-0001",op).path("nodes").get(0).path("status").asText());
        assertEquals(1,store.count("SELECT COUNT(*) FROM command_receipts"));
    }
    @Test void locationObservationDedupAndMissingMarker()throws Exception{
        ObjectNode data=input("mqtt-property.json");data.put("node_id","BOX-0001-POSE");data.put("role","POSE");data.put("caps",120);data.remove("env");
        data.putObject("pose").put("valid",false).putNull("acc_g").putNull("tilt_deg").put("state","UNKNOWN");
        ObjectNode loc=data.putObject("loc");loc.put("lat",30).put("lon",120).put("valid",true).put("src","MOCK");
        receive(data,"property");assertEquals("UNKNOWN",query.snapshot(operator(),"BOX-0001").path("loc").path("freshness").asText());
        ObjectNode observation=loc.putObject("observation");observation.put("sample_seq",1).put("ts",data.path("ts").asLong()).put("ts_src","SNTP").put("uptime_ms",30000).put("time_uncertainty_ms",1);
        data.put("property_seq",31);receive(data,"property");
        data.put("property_seq",32).put("uptime_ms",40000);receive(data,"property");
        assertEquals(1,store.count("SELECT COUNT(*) FROM location_samples"));assertEquals("FRESH",query.snapshot(operator(),"BOX-0001").path("loc").path("freshness").asText());
        data.put("property_seq",33).put("uptime_ms",45000);receive(data,"property");assertTrue(query.snapshot(operator(),"BOX-0001").path("loc").path("stale").asBoolean());
        assertEquals(1,query.history(operator(),"BOX-0001",Map.of(),true).path("total").asInt());
    }

}
