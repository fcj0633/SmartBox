package cn.smartbox;

import cn.smartbox.asset.*;
import cn.smartbox.auth.*;
import cn.smartbox.common.*;
import cn.smartbox.config.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
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
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="smartbox.workers.enabled=false")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AssetIT {
    @Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired BootstrapService bootstrap;
    @Autowired AssetBootstrapService seed;@Autowired DemoAssetConfig config;@Autowired AssetService service;@Autowired AuthMapper users;
    @LocalServerPort int port;
    JsonNode fixture;final HttpClient http=HttpClient.newHttpClient();
    @BeforeAll void accounts()throws Exception{
        TestDatabaseGuard.require(System.getenv("SPRING_PROFILES_ACTIVE"),System.getenv("SMARTBOX_DB_URL"),db.queryForObject("SELECT DATABASE()",String.class),db.queryForObject("SELECT CURRENT_USER()",String.class),System.getenv("SMARTBOX_TEST_ALLOW_RESET"));
        for(String table:List.of("transport_tasks","box_assignments","box_ownerships","enterprises","command_receipts","command_dispatch","idempotency_records","node_commands","command_operations","box_domain_views","alarm_state","alarms","box_domain_state","door_conclusions","event_evidence","event_views","processing_outbox","events","location_samples","node_latest","telemetry","node_runtime","audit_logs","user_box_access","users","nodes","boxes"))db.update("DELETE FROM "+table);
        fixture=json.readTree(Files.readString(Path.of(System.getenv("SMARTBOX_BOOTSTRAP_FILE"))));bootstrap.initialize(fixture);
    }
    @BeforeEach void assets(){db.update("DELETE FROM transport_tasks");db.update("DELETE FROM box_assignments");db.update("DELETE FROM box_ownerships");db.update("DELETE FROM enterprises");seed.initialize(config.seed());}
    JsonNode account(String role,int box){for(JsonNode u:fixture.path("users"))if(u.path("role").asText().equals(role)&&u.path("box_ids").get(0).asText().equals("BOX-000"+box))return u;throw new AssertionError();}
    long id(String role,int box){return users.byUsername(account(role,box).path("username").asText()).id();}
    CurrentUser current(String role,int box){var u=users.byId(id(role,box));return new CurrentUser(u.id(),u.username(),u.roleCode());}
    record Reply(int status,JsonNode body){}
    Reply request(String method,String path,String token,JsonNode body)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(15));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(body!=null)b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));else b.method(method,HttpRequest.BodyPublishers.noBody());
        var r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());return new Reply(r.statusCode(),json.readTree(r.body()));
    }
    String login(String role,int box)throws Exception{var a=account(role,box);var body=json.createObjectNode().put("username",a.path("username").asText()).put("password",a.path("password").asText());var r=request("POST","/auth/login",null,body);assertEquals(200,r.status());return r.body().path("data").path("access_token").asText();}
    ObjectNode rider(int box){return json.createObjectNode().put("rider_user_id",id("COURIER",box));}
    @Test void seedRepeatPreservesCredentialsGrantsAndAssignments(){
        var before=db.queryForList("SELECT * FROM users ORDER BY id");var grants=db.queryForList("SELECT * FROM user_box_access ORDER BY user_id,box_id");
        var a=service.assign(current("LOGISTICS_OPERATOR",1),"BOX-0001",id("COURIER",1));
        seed.initialize(config.seed());assertEquals(before,db.queryForList("SELECT * FROM users ORDER BY id"));assertEquals(grants,db.queryForList("SELECT * FROM user_box_access ORDER BY user_id,box_id"));
        assertEquals(a,service.assign(current("LOGISTICS_OPERATOR",1),"BOX-0001",id("COURIER",1)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM nodes WHERE box_id='BOX-DEMO-02'",Integer.class));
    }
    @Test void conflictingSeedRollsBackEarlierInsertedDemoAsset(){
        ObjectNode changed=(ObjectNode)config.seed();ArrayNode personal=(ArrayNode)changed.get("personal_boxes");
        ObjectNode newBox=((ObjectNode)personal.get(0)).deepCopy();newBox.put("box_id","BOX-ROLLBACK");personal.insert(0,newBox);
        ((ObjectNode)personal.get(1)).put("owner_username",account("COURIER",2).path("username").asText());
        assertThrows(ApiException.class,()->seed.initialize(changed));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM boxes WHERE box_id='BOX-ROLLBACK'",Integer.class));
        assertEquals(id("COURIER",1),db.queryForObject("SELECT owner_user_id FROM box_ownerships WHERE box_id='BOX-DEMO-02'",Long.class));
    }
    @Test void realHttpAssignRepeatReclaimAndOldDeviceAccessUnchanged()throws Exception{
        String operator=login("LOGISTICS_OPERATOR",1),rider=login("COURIER",1);var grants=db.queryForList("SELECT * FROM user_box_access ORDER BY user_id,box_id");
        assertEquals(200,request("GET","/enterprise/boxes",operator,null).status());
        assertEquals(200,request("GET","/boxes",rider,null).status());assertEquals(200,request("GET","/boxes/BOX-0001/latest",rider,null).status());
        var a=request("POST","/enterprise/boxes/BOX-0001/assign",operator,rider(1));assertEquals(200,a.status());
        assertEquals(a.body().path("data"),request("POST","/enterprise/boxes/BOX-0001/assign",operator,rider(1)).body().path("data"));
        var list=request("GET","/rider/boxes",rider,null);assertEquals(2,list.body().path("data").size());assertTrue(list.body().toString().contains("ASSIGNED"));assertTrue(list.body().toString().contains("OWNED"));
        assertEquals(200,request("POST","/enterprise/boxes/BOX-0001/unassign",operator,null).status());
        assertEquals(200,request("POST","/enterprise/boxes/BOX-0001/unassign",operator,null).status());assertEquals(1,request("GET","/rider/boxes",rider,null).body().path("data").size());
        assertThrows(ApiException.class,()->service.requireRiderUse(current("COURIER",1),"BOX-0001"));service.requireRiderUse(current("COURIER",1),"BOX-DEMO-02");
        assertEquals(grants,db.queryForList("SELECT * FROM user_box_access ORDER BY user_id,box_id"));assertEquals(200,request("GET","/boxes/BOX-0001/latest",rider,null).status());
    }
    @Test void crossEnterpriseRolesAndPersonalOwnershipAreEnforced()throws Exception{
        String a=login("LOGISTICS_OPERATOR",1),b=login("LOGISTICS_OPERATOR",2),r=login("COURIER",2),visitor=login("DELIVERY_USER",1);
        assertEquals(401,request("GET","/enterprise/boxes",null,null).status());assertEquals(403,request("GET","/enterprise/boxes",visitor,null).status());assertEquals(403,request("GET","/rider/boxes",a,null).status());
        for(String token:List.of(b,r,visitor)){assertEquals(403,request("POST","/enterprise/boxes/BOX-0001/assign",token,rider(1)).status());assertEquals(403,request("POST","/enterprise/boxes/BOX-0001/unassign",token,null).status());}
        assertEquals(403,request("POST","/enterprise/boxes/BOX-DEMO-02/assign",a,rider(1)).status());
        assertEquals(0,request("GET","/rider/boxes",r,null).body().path("data").size());assertFalse(request("GET","/enterprise/boxes",b,null).body().toString().contains("BOX-0001"));
        db.update("DELETE FROM box_ownerships WHERE box_id='BOX-0002'");db.update("DELETE FROM enterprises WHERE admin_user_id=?",id("LOGISTICS_OPERATOR",2));
        assertEquals(403,request("GET","/enterprise/boxes",b,null).status());
        assertThrows(ApiException.class,()->service.requireRiderUse(current("COURIER",2),"BOX-DEMO-02"));
    }
    @Test void invalidDisabledAndNonAllowlistedTargetsRejected()throws Exception{
        String a=login("LOGISTICS_OPERATOR",1);long r=id("COURIER",2);
        assertEquals(400,request("POST","/enterprise/boxes/invalid.box/assign",a,rider(1)).status());
        assertEquals(400,request("POST","/enterprise/boxes/BOX-0001/assign",a,json.createObjectNode().put("rider_user_id",-1)).status());
        assertEquals(400,request("POST","/enterprise/boxes/BOX-0001/assign",a,rider(1).put("enterprise_id",1)).status());
        assertEquals(400,request("POST","/enterprise/boxes/BOX-0001/assign",a,json.createObjectNode().put("rider_user_id",id("DELIVERY_USER",1))).status());
        String disabled=login("COURIER",2);db.update("UPDATE users SET enabled=false WHERE id=?",r);
        try{assertEquals(400,request("POST","/enterprise/boxes/BOX-0001/assign",a,rider(2)).status());assertEquals(401,request("GET","/rider/boxes",disabled,null).status());}finally{db.update("UPDATE users SET enabled=true WHERE id=?",r);}
        long now=System.currentTimeMillis();db.update("INSERT INTO users(username,password_hash,role_code,created_at,updated_at) VALUES('asset-nonlisted','not-a-login-hash','COURIER',?,?)",now,now);
        long other=db.queryForObject("SELECT id FROM users WHERE username='asset-nonlisted'",Long.class);
        try{assertEquals(400,request("POST","/enterprise/boxes/BOX-0001/assign",a,json.createObjectNode().put("rider_user_id",other)).status());}finally{db.update("DELETE FROM users WHERE id=?",other);}
    }
    @Test void competingHttpAssignmentsKeepOneCurrentRider()throws Exception{
        String a=login("LOGISTICS_OPERATOR",1);CountDownLatch start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var tasks=new ArrayList<Future<Reply>>();for(int i=1;i<=2;i++){final int rider=i;tasks.add(pool.submit(()->{start.await();return request("POST","/enterprise/boxes/BOX-0001/assign",a,rider(rider));}));}start.countDown();
            var statuses=new ArrayList<Integer>();for(var t:tasks)statuses.add(t.get(20,TimeUnit.SECONDS).status());Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM box_assignments WHERE box_id='BOX-0001'",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void mysqlRejectsConflictingOwnershipAndDuplicateAssignments(){
        assertThrows(DataAccessException.class,()->db.update("UPDATE box_ownerships SET owner_user_id=? WHERE box_id='BOX-0001'",id("COURIER",1)));
        service.assign(current("LOGISTICS_OPERATOR",1),"BOX-0001",id("COURIER",1));
        assertThrows(DataAccessException.class,()->db.update("INSERT INTO box_assignments(box_id,rider_user_id,assigned_by,assigned_at) VALUES('BOX-0001',?,?,0)",id("COURIER",2),id("LOGISTICS_OPERATOR",1)));
        assertThrows(DataAccessException.class,()->db.update("DELETE FROM box_ownerships WHERE box_id='BOX-0001'"));
    }
}
