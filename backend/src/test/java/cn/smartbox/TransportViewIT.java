package cn.smartbox;
import cn.smartbox.transport.*;
import cn.smartbox.asset.*;
import cn.smartbox.auth.*;
import cn.smartbox.config.*;
import cn.smartbox.common.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="smartbox.workers.enabled=false")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TransportViewIT {
 static class TestClock extends Clock {long now=System.currentTimeMillis();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}}
 @TestConfiguration static class TimeConfig {@Bean @Primary TestClock testClock(){return new TestClock();}}
 @Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired BootstrapService bootstrap;@Autowired AssetBootstrapService seed;@Autowired DemoAssetConfig config;@Autowired AuthMapper users;
 @Autowired GuestAccessService guest;@Autowired TransportMapper taskMapper;@Autowired TransportMonitoringMapper mapper;@Autowired TransportMonitoringService monitor;@Autowired TestClock clock;
 @LocalServerPort int port;JsonNode fixture;HttpClient http=HttpClient.newHttpClient();long t1,t2,now;String a,r,b,r2;
 @BeforeAll void setup()throws Exception{
  TestDatabaseGuard.require(System.getenv("SPRING_PROFILES_ACTIVE"),System.getenv("SMARTBOX_DB_URL"),db.queryForObject("SELECT DATABASE()",String.class),db.queryForObject("SELECT CURRENT_USER()",String.class),System.getenv("SMARTBOX_TEST_ALLOW_RESET"));
  for(String table:List.of("transport_guest_access","transport_tasks","box_assignments","box_ownerships","enterprises","command_receipts","command_dispatch","idempotency_records","node_commands","command_operations","box_domain_views","alarm_state","alarms","box_domain_state","door_conclusions","event_evidence","event_views","processing_outbox","events","location_samples","node_latest","telemetry","node_runtime","audit_logs","user_box_access","users","nodes","boxes"))db.update("DELETE FROM "+table);
  fixture=json.readTree(Files.readString(Path.of(System.getenv("SMARTBOX_BOOTSTRAP_FILE"))));bootstrap.initialize(fixture);
 }
 @BeforeEach void data()throws Exception{
  db.update("DELETE FROM transport_guest_access");db.update("DELETE FROM transport_tasks");db.update("DELETE FROM processing_outbox");db.update("DELETE FROM event_views");db.update("DELETE FROM events");db.update("DELETE FROM telemetry");db.update("DELETE FROM box_assignments");db.update("DELETE FROM box_ownerships");db.update("DELETE FROM enterprises");seed.initialize(config.seed());
  now=System.currentTimeMillis();clock.now=now;
  t1=task("TR-11111111111111111111","COMPLETED",now-60000,now-30000);t2=task("TR-22222222222222222222","ACTIVE",now-30000,null);
  a=login("LOGISTICS_OPERATOR",1);r=login("COURIER",1);b=login("LOGISTICS_OPERATOR",2);r2=login("COURIER",2);
 }
 JsonNode account(String role,int box){for(var u:fixture.path("users"))if(u.path("role").asText().equals(role)&&u.path("box_ids").get(0).asText().equals("BOX-000"+box))return u;throw new AssertionError();}
 long uid(String role,int box){return users.byUsername(account(role,box).path("username").asText()).id();}
 record Reply(int status,JsonNode data){}
 Reply call(String method,String path,String jwt,JsonNode body,String token)throws Exception{
  var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path));if(jwt!=null)builder.header("Authorization","Bearer "+jwt);if(token!=null)builder.header("X-Transport-View-Token",token);
  if(body!=null)builder.header("Content-Type","application/json");builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
  var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());return new Reply(response.statusCode(),json.readTree(response.body()));
 }
 String login(String role,int box)throws Exception{var u=account(role,box);return call("POST","/auth/login",null,json.createObjectNode().put("username",u.path("username").asText()).put("password",u.path("password").asText()),null).data().path("data").path("access_token").asText();}
 long task(String no,String status,Long start,Long end){db.update("INSERT INTO transport_tasks(transport_no,box_id,rider_id,status,item_name,started_at,ended_at,created_at,updated_at) VALUES(?,'BOX-0001',?,?,'书籍',?,?,?,?)",no,uid("COURIER",1),status,start,end,now,now);return db.queryForObject("SELECT id FROM transport_tasks WHERE transport_no=?",Long.class,no);}
 void sample(int seq,Long time,Long uncertainty,String quality){db.update("INSERT INTO telemetry(box_id,node_id,boot_id,property_seq,device_ts,ts_src,uptime_ms,time_uncertainty_ms,received_at,payload,created_at) VALUES('BOX-0001','BOX-0001-ENV',1,?,?,?,1000,?,?,?,?)",seq,time,quality,uncertainty,now,"{\"env\":{\"temp_c\":24,\"humi_pct\":55,\"valid\":true}}",now);}
 String code(long id)throws Exception{var response=call("POST","/rider/transports/"+id+"/access-code",r,null,null);assertEquals(200,response.status());return response.data().path("data").path("code").asText();}
 Reply verify(String no,String code)throws Exception{return call("POST","/public/transport-access/verify",null,json.createObjectNode().put("transport_no",no).put("code",code),null);}
 String token(String code)throws Exception{var response=verify("TR-22222222222222222222",code);assertEquals(200,response.status());return response.data().path("data").path("token").asText();}
 @Test void trustedBoundariesSeparateTwoTransportsAndLateData()throws Exception{
  sample(1,now-45000,100L,"SNTP");sample(2,now-15000,100L,"SNTP");sample(3,null,null,"UNSYNCED");sample(4,now-30000,100L,"SNTP");sample(5,now-60000,0L,"SNTP");sample(6,now-30000,0L,"SNTP");
  var first=call("GET","/rider/transports/"+t1+"/monitoring",r,null,null);assertEquals(200,first.status());assertEquals(2,first.data().path("data").path("samples").size());
  var second=call("GET","/rider/transports/"+t2+"/monitoring",r,null,null);assertEquals(2,second.data().path("data").path("samples").size());
  sample(7,now-40000,0L,"SNTP");assertEquals(3,call("GET","/rider/transports/"+t1+"/monitoring",r,null,null).data().path("data").path("samples").size());
  assertEquals("UNKNOWN",first.data().path("data").path("environment").path("assessment").asText());
  sample(8,now-35000,0L,"SNTP");db.update("UPDATE telemetry SET payload=JSON_SET(payload,'$.env.valid',false) WHERE property_seq=8");
  var invalid=call("GET","/rider/transports/"+t1+"/monitoring",r,null,null).data().path("data").path("environment");assertEquals("INVALID",invalid.path("data_state").asText());assertTrue(invalid.path("latest_temperature").isNull());
 }
 @Test void guestOnlyReadsTokenTaskAndNotDeviceInterfaces()throws Exception{
  String value=code(t2);assertTrue(value.matches("[A-Za-z0-9]{10}"));String session=token(value);
  var response=call("GET","/public/transport-view",null,null,session);assertEquals(200,response.status());assertEquals(t2,response.data().path("data").path("task").path("task_id").asLong());assertFalse(response.data().toString().contains("box_id"));
  assertEquals(400,call("GET","/public/transport-view?task_id="+t1,null,null,session).status());assertEquals(401,call("GET","/boxes/BOX-0001/latest",null,null,session).status());
  assertEquals(401,verify("TR-22222222222222222222","wrong").status());assertEquals(401,verify("无效运输编号","wrong").status());assertEquals(401,call("GET","/public/transport-view",null,null,null).status());
  assertNotEquals(value,db.queryForObject("SELECT code_hash FROM transport_guest_access WHERE task_id=?",String.class,t2));
 }
 @Test void replacingCodeAndSessionRevokesOldAccess()throws Exception{
  String first=code(t2),session1=token(first),session2=token(first);
  assertEquals(401,call("GET","/public/transport-view",null,null,session1).status());assertEquals(200,call("GET","/public/transport-view",null,null,session2).status());
  String second=code(t2);assertNotEquals(first,second);assertEquals(401,call("GET","/public/transport-view",null,null,session2).status());assertEquals(401,verify("TR-22222222222222222222",first).status());assertEquals(200,call("GET","/public/transport-view",null,null,token(second)).status());
 }
 @Test void enterpriseAndRiderScopeAndCandidateAllowlist()throws Exception{
  assertEquals(2,call("GET","/enterprise/riders",a,null,null).data().path("data").size());assertEquals(403,call("GET","/enterprise/riders",r,null,null).status());
  assertEquals(2,call("GET","/enterprise/transports",a,null,null).data().path("data").size());assertEquals(0,call("GET","/enterprise/transports",b,null,null).data().path("data").size());
  assertEquals(404,call("GET","/enterprise/transports/"+t1,b,null,null).status());assertEquals(404,call("GET","/rider/transports/"+t1+"/monitoring",r2,null,null).status());assertEquals(404,call("POST","/rider/transports/"+t1+"/access-code",r2,null,null).status());
  long ready=task("TR-33333333333333333333","READY",null,null);assertEquals(409,call("POST","/rider/transports/"+ready+"/access-code",r,null,null).status());
  assertEquals("READY",call("GET","/rider/transports/"+ready+"/monitoring",r,null,null).data().path("data").path("environment").path("data_state").asText());
 }
 @Test void expiryAndPostCompletionWindowUseControlledClock()throws Exception{
  var current=users.byId(uid("COURIER",1));var user=new CurrentUser(current.id(),current.username(),current.roleCode());
  String code=guest.issue(user,t1).code();var session=guest.verify("TR-11111111111111111111",code,"expiry-test");clock.now=session.expires_at();assertThrows(ApiException.class,()->guest.view(session.token()));
  clock.now=now-30000+7*86400000L;assertThrows(ApiException.class,()->guest.issue(user,t1));assertThrows(ApiException.class,()->guest.verify("TR-11111111111111111111",code,"expiry-test"));
 }
 @Test void failedAttemptsAreBoundedPerSourceAndWindow(){
  var limiter=new GuestAccessService(taskMapper,mapper,monitor,clock);
  for(int i=0;i<5;i++){var ex=assertThrows(ApiException.class,()->limiter.verify("missing","wrong","rate-test"));assertEquals(401,ex.status());}
  assertEquals(429,assertThrows(ApiException.class,()->limiter.verify("other-number","wrong","rate-test")).status());clock.now+=900000;assertEquals(401,assertThrows(ApiException.class,()->limiter.verify("missing","wrong","rate-test")).status());
 }
}
