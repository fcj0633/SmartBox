package cn.smartbox;

import cn.smartbox.asset.AssetBootstrapService;
import cn.smartbox.asset.DemoAssetConfig;
import cn.smartbox.auth.AuthMapper;
import cn.smartbox.config.BootstrapService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="smartbox.workers.enabled=false")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TransportIT {
    @Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired BootstrapService bootstrap;
    @Autowired AssetBootstrapService seed;@Autowired DemoAssetConfig config;@Autowired AuthMapper users;
    @LocalServerPort int port;
    JsonNode fixture;final HttpClient http=HttpClient.newHttpClient();
    @BeforeAll void accounts()throws Exception{
        TestDatabaseGuard.require(System.getenv("SPRING_PROFILES_ACTIVE"),System.getenv("SMARTBOX_DB_URL"),db.queryForObject("SELECT DATABASE()",String.class),db.queryForObject("SELECT CURRENT_USER()",String.class),System.getenv("SMARTBOX_TEST_ALLOW_RESET"));
        for(String table:List.of("transport_guest_access","transport_tasks","box_assignments","box_ownerships","enterprises","command_receipts","command_dispatch","idempotency_records","node_commands","command_operations","box_domain_views","alarm_state","alarms","box_domain_state","door_conclusions","event_evidence","event_views","processing_outbox","events","location_samples","node_latest","telemetry","node_runtime","audit_logs","user_box_access","users","nodes","boxes"))db.update("DELETE FROM "+table);
        fixture=json.readTree(Files.readString(Path.of(System.getenv("SMARTBOX_BOOTSTRAP_FILE"))));bootstrap.initialize(fixture);
    }
    @BeforeEach void reset(){
        db.update("DELETE FROM transport_guest_access");db.update("DELETE FROM transport_tasks");db.update("DELETE FROM box_assignments");db.update("DELETE FROM box_ownerships");db.update("DELETE FROM enterprises");
        db.update("DELETE FROM node_runtime");db.update("DELETE FROM alarm_state");
        seed.initialize(config.seed());
        long now=System.currentTimeMillis();
        for(String node:db.queryForList("SELECT node_id FROM nodes WHERE box_id='BOX-0001' AND required=1 ORDER BY node_id",String.class))
            db.update("INSERT INTO node_runtime(node_id,boot_id,online,last_seen,uptime_ms,ts_src) VALUES(?,?,?,?,?,?)",node,1,true,now,1000,"SNTP");
        db.update("INSERT INTO alarm_state(box_id,assessment,active,level,updated_at) VALUES('BOX-0001','EVALUATED',0,NULL,?)",now);
    }
    JsonNode account(String role,int box){for(JsonNode u:fixture.path("users"))if(u.path("role").asText().equals(role)&&u.path("box_ids").get(0).asText().equals("BOX-000"+box))return u;throw new AssertionError("missing test account");}
    long id(String role,int box){return users.byUsername(account(role,box).path("username").asText()).id();}
    record Reply(int status,JsonNode body){}
    Reply request(String method,String path,String token,JsonNode body)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(body!=null)b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else b.method(method,HttpRequest.BodyPublishers.noBody());
        var r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());return new Reply(r.statusCode(),json.readTree(r.body()));
    }
    String login(String role,int box)throws Exception{var a=account(role,box);ObjectNode body=json.createObjectNode().put("username",a.path("username").asText()).put("password",a.path("password").asText());var r=request("POST","/auth/login",null,body);assertEquals(200,r.status());return r.body().path("data").path("access_token").asText();}
    Reply assign(String operator,String box,long riderId)throws Exception{return request("POST","/enterprise/boxes/"+box+"/assign",operator,json.createObjectNode().put("rider_user_id",riderId));}
    ObjectNode create(String rider,String box)throws Exception{var r=request("POST","/rider/transports",rider,json.createObjectNode().put("box_id",box));assertEquals(200,r.status());return (ObjectNode)r.body().path("data");}
    ObjectNode item(String rider,long task,String name,String description)throws Exception{ObjectNode body=json.createObjectNode().put("name",name);if(description!=null)body.put("description",description);var r=request("POST","/rider/transports/"+task+"/item",rider,body);assertEquals(200,r.status());return (ObjectNode)r.body().path("data");}
    Reply start(String rider,long task,boolean confirm)throws Exception{return request("POST","/rider/transports/"+task+"/start",rider,json.createObjectNode().put("confirm_risk",confirm));}
    Reply finish(String rider,long task)throws Exception{return request("POST","/rider/transports/"+task+"/finish",rider,null);}
    long taskId(JsonNode data){return data.path("task_id").asLong();}
    void error(Reply reply,int status,String code){assertEquals(status,reply.status());assertEquals(code,reply.body().path("error").path("code").asText());}
    int active(String box){return db.queryForObject("SELECT COUNT(*) FROM transport_tasks WHERE box_id=? AND status='ACTIVE'",Integer.class,box);}

    @Test void fullFlowAndCompletedHistoryIsStable()throws Exception{
        String operator=login("LOGISTICS_OPERATOR",1),rider=login("COURIER",1);assertEquals(200,assign(operator,"BOX-0001",id("COURIER",1)).status());
        ObjectNode first=create(rider,"BOX-0001");long firstId=taskId(first);assertEquals("READY",first.path("status").asText());assertTrue(first.path("transport_no").asText().matches("TR-[0-9A-F]{20}"));
        ObjectNode edited=item(rider,firstId,"  书籍  ","演示物品");assertEquals("书籍",edited.path("item_name").asText());
        ObjectNode active=(ObjectNode)start(rider,firstId,false).body().path("data");assertEquals("ACTIVE",active.path("status").asText());long started=active.path("started_at").asLong();assertTrue(started>0);
        ObjectNode repeat=(ObjectNode)start(rider,firstId,false).body().path("data");assertEquals(started,repeat.path("started_at").asLong());
        ObjectNode done=(ObjectNode)finish(rider,firstId).body().path("data");assertEquals("COMPLETED",done.path("status").asText());long ended=done.path("ended_at").asLong();assertTrue(ended>=started);
        ObjectNode doneAgain=(ObjectNode)finish(rider,firstId).body().path("data");assertEquals(ended,doneAgain.path("ended_at").asLong());
        JsonNode list=request("GET","/rider/transports",rider,null).body().path("data");assertTrue(list.isArray()&&list.size()>=1);
        assertEquals("书籍",request("GET","/rider/transports/"+firstId,rider,null).body().path("data").path("item_name").asText());
        ObjectNode second=create(rider,"BOX-0001");long secondId=taskId(second);item(rider,secondId,"第二件",null);
        assertEquals("ACTIVE",start(rider,secondId,false).body().path("data").path("status").asText());finish(rider,secondId);
        assertEquals("书籍",request("GET","/rider/transports/"+firstId,rider,null).body().path("data").path("item_name").asText());
        assertEquals(ended,request("GET","/rider/transports/"+firstId,rider,null).body().path("data").path("ended_at").asLong());
        assertEquals(200,request("POST","/enterprise/boxes/BOX-0001/unassign",operator,null).status());
        assertFalse(request("GET","/rider/boxes",rider,null).body().path("data").toString().contains("BOX-0001"));
    }
    @Test void sameBoxRejectsSecondActiveAndAllowsAfterCompletion()throws Exception{
        String operator=login("LOGISTICS_OPERATOR",1),rider=login("COURIER",1);assertEquals(200,assign(operator,"BOX-0001",id("COURIER",1)).status());
        long a=taskId(create(rider,"BOX-0001")),b=taskId(create(rider,"BOX-0001"));item(rider,a,"A",null);item(rider,b,"B",null);
        assertEquals(200,start(rider,a,false).status());error(start(rider,b,false),409,"BOX_IN_USE");assertEquals(1,active("BOX-0001"));
        finish(rider,a);assertEquals(200,start(rider,b,false).status());assertEquals(1,active("BOX-0001"));finish(rider,b);
    }
    @Test void unassignIsBlockedWhileActiveAndReclaimBlocksStart()throws Exception{
        String operator=login("LOGISTICS_OPERATOR",1),rider=login("COURIER",1);assign(operator,"BOX-0001",id("COURIER",1));
        long task=taskId(create(rider,"BOX-0001"));item(rider,task,"A",null);start(rider,task,false);
        error(request("POST","/enterprise/boxes/BOX-0001/unassign",operator,null),409,"BOX_IN_USE");
        finish(rider,task);long ready=taskId(create(rider,"BOX-0001"));item(rider,ready,"B",null);
        assertEquals(200,request("POST","/enterprise/boxes/BOX-0001/unassign",operator,null).status());
        error(start(rider,ready,false),403,"FORBIDDEN");
    }
    @Test void offlineAndNodeLessBoxCannotStartButActiveCanFinish()throws Exception{
        String operator=login("LOGISTICS_OPERATOR",1),rider=login("COURIER",1);assign(operator,"BOX-0001",id("COURIER",1));
        long task=taskId(create(rider,"BOX-0001"));item(rider,task,"A",null);
        String node=db.queryForObject("SELECT node_id FROM nodes WHERE box_id='BOX-0001' AND required=1 ORDER BY node_id LIMIT 1",String.class);
        db.update("UPDATE node_runtime SET online=0 WHERE node_id=?",node);
        error(start(rider,task,false),409,"BOX_OFFLINE");
        db.update("UPDATE node_runtime SET online=1,last_seen=? WHERE node_id=?",System.currentTimeMillis(),node);
        assertEquals(200,start(rider,task,false).status());
        db.update("UPDATE node_runtime SET online=0 WHERE node_id=?",node);
        assertEquals("COMPLETED",finish(rider,task).body().path("data").path("status").asText());
        long personal=taskId(create(rider,"BOX-DEMO-02"));item(rider,personal,"无设备",null);error(start(rider,personal,false),409,"BOX_OFFLINE");
    }
    @Test void riskConfirmationIsExplicitAndScoped()throws Exception{
        String operator=login("LOGISTICS_OPERATOR",1),rider=login("COURIER",1);assign(operator,"BOX-0001",id("COURIER",1));
        long task=taskId(create(rider,"BOX-0001"));item(rider,task,"A",null);
        db.update("DELETE FROM alarm_state WHERE box_id='BOX-0001'");error(start(rider,task,false),409,"RISK_CONFIRMATION_REQUIRED");
        assertEquals(200,start(rider,task,true).status());finish(rider,task);
        db.update("INSERT INTO alarm_state(box_id,assessment,active,level,updated_at) VALUES('BOX-0001','EVALUATED',1,'WARNING',?)",System.currentTimeMillis());
        long second=taskId(create(rider,"BOX-0001"));item(rider,second,"B",null);error(start(rider,second,false),409,"RISK_CONFIRMATION_REQUIRED");
        assertEquals(200,start(rider,second,true).status());finish(rider,second);
    }
    @Test void authorizationHidesOtherTasksAndRejectsWrongRole()throws Exception{
        String operator=login("LOGISTICS_OPERATOR",1),rider=login("COURIER",1),other=login("COURIER",2),visitor=login("DELIVERY_USER",1);
        assign(operator,"BOX-0001",id("COURIER",1));long task=taskId(create(rider,"BOX-0001"));item(rider,task,"A",null);
        error(request("GET","/rider/transports/"+task,other,null),404,"NOT_FOUND");
        error(request("POST","/rider/transports/"+task+"/finish",other,null),404,"NOT_FOUND");
        error(request("GET","/rider/transports",operator,null),403,"FORBIDDEN");
        error(request("GET","/rider/transports",visitor,null),403,"FORBIDDEN");
        assertEquals(401,request("GET","/rider/transports",null,null).status());
        error(request("POST","/rider/transports",other,json.createObjectNode().put("box_id","BOX-0001")),403,"FORBIDDEN");
        long personal=taskId(create(rider,"BOX-DEMO-02"));error(request("GET","/rider/transports/"+personal,other,null),404,"NOT_FOUND");
    }
    @Test void itemValidationAndImmutableAfterStart()throws Exception{
        String operator=login("LOGISTICS_OPERATOR",1),rider=login("COURIER",1);assign(operator,"BOX-0001",id("COURIER",1));
        long task=taskId(create(rider,"BOX-0001"));
        assertEquals(200,request("POST","/rider/transports/"+task+"/item",rider,json.createObjectNode().put("name","字".repeat(50)).put("description","d".repeat(200))).status());
        error(request("POST","/rider/transports/"+task+"/item",rider,json.createObjectNode().put("name","字".repeat(51))),400,"VALIDATION_ERROR");
        error(request("POST","/rider/transports/"+task+"/item",rider,json.createObjectNode().put("name","x").put("description","d".repeat(201))),400,"VALIDATION_ERROR");
        error(request("POST","/rider/transports/"+task+"/item",rider,json.createObjectNode().put("name","   ")),400,"VALIDATION_ERROR");
        error(request("POST","/rider/transports/"+task+"/item",rider,json.createObjectNode().put("name","x").put("rider_id",1)),400,"VALIDATION_ERROR");
        assertEquals("📦",request("POST","/rider/transports/"+task+"/item",rider,json.createObjectNode().put("name","📦")).body().path("data").path("item_name").asText());
        assertEquals(200,start(rider,task,false).status());
        error(request("POST","/rider/transports/"+task+"/item",rider,json.createObjectNode().put("name","changed")),409,"INVALID_TASK_STATE");
    }
    @Test void concurrentStartsKeepExactlyOneActive()throws Exception{
        String operator=login("LOGISTICS_OPERATOR",1),rider=login("COURIER",1);assign(operator,"BOX-0001",id("COURIER",1));
        long a=taskId(create(rider,"BOX-0001")),b=taskId(create(rider,"BOX-0001"));item(rider,a,"A",null);item(rider,b,"B",null);
        CountDownLatch gate=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            var tasks=List.of(pool.submit(()->{gate.await();return start(rider,a,false);}),pool.submit(()->{gate.await();return start(rider,b,false);}));
            gate.countDown();List<Integer> statuses=new ArrayList<>();for(var t:tasks)statuses.add(t.get(20,TimeUnit.SECONDS).status());statuses.sort(Integer::compareTo);
            assertEquals(List.of(200,409),statuses);assertEquals(1,active("BOX-0001"));
        }finally{pool.shutdownNow();}
    }
    @Test void legacyAssetAndDeviceQueriesStillWork()throws Exception{
        String operator=login("LOGISTICS_OPERATOR",1),rider=login("COURIER",1);
        assertEquals(200,request("GET","/enterprise/boxes",operator,null).status());assertEquals(200,request("GET","/rider/boxes",rider,null).status());
        assertEquals(200,request("GET","/boxes",rider,null).status());assertEquals(200,request("GET","/boxes/BOX-0001/latest",rider,null).status());
    }
}
