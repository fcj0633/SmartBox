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
            for(String table:List.of("box_assignments","box_ownerships","enterprises","command_receipts","command_dispatch","idempotency_records","node_commands","command_operations","processing_outbox","event_evidence","box_domain_state","door_conclusions","alarms","alarm_state","box_domain_views","event_views","location_samples","node_runtime","audit_logs","events","node_latest","telemetry","user_box_access","users","nodes","boxes"))db.update("DELETE FROM "+table);
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
    @Autowired DomainEventWorker domainWorker;
    @BeforeEach void clearLive(){
        for(String table:List.of("box_assignments","box_ownerships","enterprises","command_receipts","command_dispatch","idempotency_records","node_commands","command_operations","processing_outbox","event_evidence","box_domain_state","door_conclusions","alarms","alarm_state","box_domain_views","event_views","events","location_samples","node_latest","telemetry","node_runtime"))db.update("DELETE FROM "+table);
        db.update("UPDATE nodes SET current_boot_id=NULL,last_received_at=NULL");
        db.update("UPDATE boxes SET sentry_revision=0");
    }
    CurrentUser operator(){UserAccount a=users.byUsername(account("LOGISTICS_OPERATOR",1).path("username").asText());return new CurrentUser(a.id(),a.username(),a.roleCode());}
    ObjectNode input(String file)throws Exception{return (ObjectNode)json.readTree(Files.readString(Path.of("../contracts/mqtt/fixtures/positive/"+file)));}
    void receive(ObjectNode data,String suffix){ingestion.accept(new MqttGateway.Packet("box/BOX-0001/"+data.path("node_id").asText()+"/"+suffix,validator.text(data).getBytes(java.nio.charset.StandardCharsets.UTF_8),suffix.equals("property")?0:1,false));}
    ObjectNode event()throws Exception {
        ObjectNode data=input("mqtt-event.json");return data;
    }
    ObjectNode domainEvent(String node,long boot,long seq,String type,String source,String level,String data)throws Exception{
        ObjectNode envelope=json.createObjectNode();envelope.put("v",1);envelope.put("box_id","BOX-0001");envelope.put("node_id",node);envelope.put("boot_id",boot);envelope.put("ts",1800000000000L);envelope.put("ts_src","SNTP");envelope.put("uptime_ms",30000);envelope.put("time_uncertainty_ms",100);
        ObjectNode e=envelope.putArray("events").addObject();e.put("event_seq",seq);e.put("ts",1800000000000L);e.put("ts_src","SNTP");e.put("uptime_ms",30000);e.put("time_uncertainty_ms",100);e.put("type",type);e.put("src",source);e.put("level",level);e.set("data",json.readTree(data));e.put("replayed",false);return envelope;
    }

    @Test void batchCommitDedupCanonicalConflictRollback()throws Exception{
        var data=event();receive(data,"event");receive(data,"event");
        assertEquals(1,store.count("SELECT COUNT(*) FROM events"));assertEquals(2,store.count("SELECT COUNT(*) FROM processing_outbox"));
        ((ObjectNode)data.path("events").get(0)).put("replayed",true);receive(data,"event");
        ((ObjectNode)data.path("events").get(0).path("data")).put("lux",999);
        ObjectNode newEvent=((ObjectNode)data.path("events").get(0)).deepCopy();newEvent.put("event_seq",2);((ArrayNode)data.path("events")).insert(0,newEvent);
        assertThrows(ApiException.class,()->receive(data,"event"));assertEquals(1,store.count("SELECT COUNT(*) FROM events"));
    }
    @Test void outboxLeaseOwnerAndDurableEffect()throws Exception{
        receive(event(),"event");var work=worker.claim("first",System.currentTimeMillis());assertNotNull(work);
        assertFalse(worker.complete(work,"wrong"));assertTrue(worker.complete(work,"first"));
        assertEquals(1,store.count("SELECT COUNT(*) FROM event_views"));assertNull(worker.claim("second",System.currentTimeMillis()));
        assertEquals("DONE",store.one("SELECT status FROM processing_outbox WHERE task_type='EVENT_VIEW'").get("status"));
    }
    @Test void abandonedLeaseCanBeReclaimed()throws Exception{
        receive(event(),"event");var first=worker.claim("first",System.currentTimeMillis());
        store.update("UPDATE processing_outbox SET locked_until=0");var second=worker.claim("second",System.currentTimeMillis());
        assertNotNull(second);assertFalse(worker.complete(first,"first"));assertTrue(worker.complete(second,"second"));
    }
    @Test void environmentAlarmStateAndRecoveryAreProjected()throws Exception{
        ObjectNode first=event();JsonNode firstEvent=first.path("events").get(0);
        ((ObjectNode)firstEvent).put("type","ENV_HIGH").put("level","WARNING");
        ((ObjectNode)firstEvent).set("data",json.readTree("{\"metric\":\"temp_c\",\"value\":9,\"threshold\":8,\"profile_id\":\"demo\"}"));
        receive(first,"event");domainWorker.runBatch();
        var active=query.snapshot(operator(),"BOX-0001").path("alarm");
        assertEquals("EVALUATED",active.path("assessment").asText());assertTrue(active.path("active").asBoolean());assertEquals("WARNING",active.path("level").asText());
        ObjectNode recovered=first.deepCopy();((ObjectNode)recovered.path("events").get(0)).put("event_seq",2).put("type","ENV_RECOVERED");
        ((ObjectNode)recovered.path("events").get(0)).set("data",json.readTree("{\"metric\":\"temp_c\",\"value\":7,\"threshold\":7,\"profile_id\":\"demo\"}"));
        receive(recovered,"event");domainWorker.runBatch();
        var inactive=query.snapshot(operator(),"BOX-0001").path("alarm");
        assertEquals("EVALUATED",inactive.path("assessment").asText());assertFalse(inactive.path("active").asBoolean());assertTrue(inactive.path("level").isNull());
        assertEquals(2,store.count("SELECT COUNT(*) FROM box_domain_views WHERE event_type='ALARM'"));
    }
    @Test void singleDoorEvidenceWaitsThenKeepsUnknown()throws Exception{
        receive(event(),"event");domainWorker.runBatch();
        assertEquals(0,store.count("SELECT COUNT(*) FROM door_conclusions"));
        store.update("UPDATE event_evidence SET received_at=?",System.currentTimeMillis()-2100);
        store.update("UPDATE processing_outbox SET next_attempt_at=0 WHERE task_type='DOMAIN_EVENT'");
        domainWorker.runBatch();
        var door=query.snapshot(operator(),"BOX-0001").path("door");
        assertEquals("UNKNOWN",door.path("state").asText());assertEquals("INSUFFICIENT",door.path("confidence").asText());
        assertEquals("UNKNOWN",query.snapshot(operator(),"BOX-0001").path("alarm").path("assessment").asText());
        assertEquals(1,store.count("SELECT COUNT(*) FROM door_conclusions WHERE reason='LIGHT_ONLY'"),
            "evidence="+store.count("SELECT COUNT(*) FROM event_evidence")+
            ", outbox="+store.count("SELECT COUNT(*) FROM processing_outbox WHERE task_type='DOMAIN_EVENT' AND status='RETRY'")+
            ", latest_error="+Optional.ofNullable(store.one("SELECT last_error_code FROM processing_outbox WHERE task_type='DOMAIN_EVENT' ORDER BY id DESC LIMIT 1")).map(r->r.get("last_error_code")).orElse("none"));
        validator.validate("Snapshot",query.snapshot(operator(),"BOX-0001"));
        var delivery=new CurrentUser(operator().id(),operator().username(),"DELIVERY_USER");
        JsonNode view=query.history(delivery,"BOX-0001",Map.of(),false).path("items").get(0);
        assertEquals("DOOR_CONCLUSION",view.path("type").asText());assertFalse(view.has("evidence"));validator.validate("DeliveryEventView",view);
    }
    @Test void oppositeSensorDoorEvidenceConcludesMediumWhenNodeHealthIsNotProven()throws Exception{
        receive(domainEvent("BOX-0001-ENV",12,1,"DOOR_EVIDENCE","LIGHT","INFO","{\"action\":\"OPEN\",\"lux\":120,\"actuator_excluded\":true}"),"event");
        receive(domainEvent("BOX-0001-POSE",13,1,"DOOR_EVIDENCE","TILT","INFO","{\"action\":\"OPEN\",\"tilt_deg\":70,\"actuator_excluded\":true}"),"event");
        domainWorker.runBatch();
        assertEquals(0,store.count("SELECT COUNT(*) FROM door_conclusions"));
        Thread.sleep(2100);domainWorker.runBatch();
        var door=query.snapshot(operator(),"BOX-0001").path("door");
        assertEquals("OPEN",door.path("state").asText());assertEquals("MEDIUM",door.path("confidence").asText());
        assertEquals(2,store.count("SELECT COUNT(*) FROM event_evidence"));assertEquals(1,store.count("SELECT COUNT(*) FROM door_conclusions"));
        assertEquals("UNKNOWN",query.snapshot(operator(),"BOX-0001").path("alarm").path("assessment").asText());
    }
    @Test void geofenceExitStartsWarningAndEnterRecoversIt()throws Exception{
        receive(domainEvent("BOX-0001-POSE",13,1,"GEOFENCE_EXIT","MOCK","WARNING","{\"lat\":30.001,\"lon\":120.001,\"distance_m\":157,\"src\":\"MOCK\"}"),"event");domainWorker.runBatch();
        var active=query.snapshot(operator(),"BOX-0001").path("alarm");assertTrue(active.path("active").asBoolean());assertEquals("WARNING",active.path("level").asText());
        receive(domainEvent("BOX-0001-POSE",13,2,"GEOFENCE_ENTER","MOCK","INFO","{\"lat\":30.0,\"lon\":120.0,\"distance_m\":8,\"src\":\"MOCK\"}"),"event");domainWorker.runBatch();
        var recovered=query.snapshot(operator(),"BOX-0001").path("alarm");assertFalse(recovered.path("active").asBoolean());assertTrue(recovered.path("level").isNull());
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


    @Autowired org.springframework.core.env.Environment domainEnvironment;
    DomainEventWorker domainAt(long now){return new DomainEventWorker(store,validator,Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC),manager,domainEnvironment);}
    long lastReceived(){return ((Number)store.one("SELECT MAX(received_at) AS t FROM events").get("t")).longValue();}
    void bothOnline()throws Exception{
        var env=input("mqtt-heartbeat.json");env.put("boot_id",12);receive(env,"heartbeat");
        var pose=input("mqtt-pose-heartbeat.json");pose.put("boot_id",13);receive(pose,"heartbeat");
    }
    @ParameterizedTest @CsvSource({"0,500,HIGH","2000,500,HIGH","0,501,MEDIUM","2001,500,MEDIUM"})
    void doorPairTimeAndQualityBoundaries(long delta,long uncertainty,String expected)throws Exception{
        bothOnline();
        var first=domainEvent("BOX-0001-ENV",12,1,"DOOR_EVIDENCE","LIGHT","INFO","{\"action\":\"OPEN\",\"lux\":120,\"actuator_excluded\":true}");
        var second=domainEvent("BOX-0001-POSE",13,1,"DOOR_EVIDENCE","TILT","INFO","{\"action\":\"OPEN\",\"tilt_deg\":70,\"actuator_excluded\":true}");
        ((ObjectNode)first.path("events").get(0)).put("time_uncertainty_ms",uncertainty);
        ((ObjectNode)second.path("events").get(0)).put("ts",1800000000000L+delta);
        receive(first,"event");receive(second,"event");long start=((Number)store.one("SELECT MIN(received_at) AS t FROM events").get("t")).longValue();
        domainAt(start).runBatch();assertEquals(0,store.count("SELECT COUNT(*) FROM door_conclusions"));
        domainAt(start+1999).runBatch();assertEquals(0,store.count("SELECT COUNT(*) FROM door_conclusions"));
        domainAt(start+2100).runBatch();
        var door=query.snapshot(operator(),"BOX-0001").path("door");assertEquals("OPEN",door.path("state").asText());assertEquals(expected,door.path("confidence").asText());
        assertEquals(2,store.count("SELECT COUNT(*) FROM event_evidence WHERE conclusion_id IS NOT NULL"));
    }
    @ParameterizedTest @CsvSource({"30,UNKNOWN","60,UNKNOWN","60.01,OPEN","70,OPEN"})
    void singleTiltRequiresStrictlyOverSixty(double tilt,String state)throws Exception{
        receive(domainEvent("BOX-0001-POSE",13,1,"DOOR_EVIDENCE","TILT","INFO","{\"action\":\"OPEN\",\"tilt_deg\":"+tilt+",\"actuator_excluded\":true}"),"event");
        domainAt(lastReceived()+2100).runBatch();var door=query.snapshot(operator(),"BOX-0001").path("door");assertEquals(state,door.path("state").asText());
    }
    @Test void repeatedKnownDoorEvidenceLinksWithoutDuplicateConclusion()throws Exception{
        for(int seq=1;seq<=2;seq++){
            receive(domainEvent("BOX-0001-POSE",13,seq,"DOOR_EVIDENCE","TILT","INFO","{\"action\":\"OPEN\",\"tilt_deg\":70,\"actuator_excluded\":true}"),"event");domainAt(lastReceived()+2100).runBatch();
        }
        assertEquals(1,store.count("SELECT COUNT(*) FROM door_conclusions"));assertEquals(1,store.count("SELECT COUNT(*) FROM box_domain_views WHERE event_type='DOOR_CONCLUSION'"));
        assertEquals(2,store.count("SELECT COUNT(*) FROM event_evidence WHERE conclusion_id IS NOT NULL"));
    }
    @Test void historicalEvidenceCannotChangeCurrentDoor()throws Exception{
        receive(domainEvent("BOX-0001-POSE",13,1,"DOOR_EVIDENCE","TILT","INFO","{\"action\":\"OPEN\",\"tilt_deg\":70,\"actuator_excluded\":true}"),"event");domainAt(lastReceived()+2100).runBatch();
        var history=domainEvent("BOX-0001-POSE",13,2,"DOOR_EVIDENCE","TILT","INFO","{\"action\":\"CLOSE\",\"tilt_deg\":0,\"actuator_excluded\":true}");((ObjectNode)history.path("events").get(0)).put("replayed",true);
        receive(history,"event");domainAt(lastReceived()+2200).runBatch();
        assertEquals("OPEN",query.snapshot(operator(),"BOX-0001").path("door").path("state").asText());
        assertEquals(1,store.count("SELECT COUNT(*) FROM door_conclusions WHERE historical=1"));
    }

    @Test void committedDoorPairBacklogSurvivesConcurrentWorkers()throws Exception{
        bothOnline();
        receive(domainEvent("BOX-0001-ENV",12,1,"DOOR_EVIDENCE","LIGHT","INFO","{\"action\":\"OPEN\",\"lux\":120,\"actuator_excluded\":true}"),"event");
        receive(domainEvent("BOX-0001-POSE",13,1,"DOOR_EVIDENCE","TILT","INFO","{\"action\":\"OPEN\",\"tilt_deg\":70,\"actuator_excluded\":true}"),"event");
        var processor=domainAt(lastReceived()+2100);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->processor.runBatch());var b=pool.submit(()->processor.runBatch());a.get(15,java.util.concurrent.TimeUnit.SECONDS);b.get(15,java.util.concurrent.TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(1,store.count("SELECT COUNT(*) FROM door_conclusions"));
        assertEquals("HIGH",query.snapshot(operator(),"BOX-0001").path("door").path("confidence").asText());
        assertEquals(2,store.count("SELECT COUNT(*) FROM event_evidence WHERE conclusion_id IS NOT NULL"));
    }
}
