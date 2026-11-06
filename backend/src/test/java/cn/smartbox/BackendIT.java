package cn.smartbox;
import cn.smartbox.auth.*;
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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackendIT {
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
            for(String table:List.of("audit_logs","idempotency_records","node_commands","command_operations","processing_outbox","events","node_latest","telemetry","user_box_access","users","nodes","boxes"))db.update("DELETE FROM "+table);
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
    @Test void thirteenTablesAndFourMigrations(){
        assertEquals(13,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'",Integer.class));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL",Integer.class));
        flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
        assertEquals(8,db.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
    }
    @Test void runtimeHasNoDdlPrivilege(){assertThrows(DataAccessException.class,()->db.execute("CREATE TABLE forbidden_probe(id INT)"));}
    @Test void bootstrapRepeatAndConflictRollback(){
        var before=db.queryForList("SELECT id,username,password_hash,created_at,updated_at FROM users ORDER BY id");bootstrap.initialize(fixture);
        assertTrue(before.equals(db.queryForList("SELECT id,username,password_hash,created_at,updated_at FROM users ORDER BY id")),"Repeated initialization must not rewrite account rows");
        ObjectNode conflicting=fixture.deepCopy();((ArrayNode)conflicting.get("boxes")).add("BOX-CONFLICT");((ObjectNode)conflicting.get("users").get(0)).put("password",UUID.randomUUID().toString());
        assertThrows(ApiException.class,()->bootstrap.initialize(conflicting));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM boxes WHERE box_id='BOX-CONFLICT'",Integer.class));
    }
    @Test void usernameCaseAndTrailingSpacesRemainDistinct(){
        String name=account("COURIER",1).get("username").asText();
        assertNull(users.byUsername(name.toUpperCase(Locale.ROOT)));assertNull(users.byUsername(name+" "));
    }
    @ParameterizedTest @ValueSource(strings={"LOGISTICS_VIEWER","LOGISTICS_OPERATOR","DELIVERY_USER","COURIER"})
    void actualHttpRoleAndBoxMatrix(String role)throws Exception{
        for(int box=1;box<=2;box++){
            String token=login(account(role,box));Reply me=request("GET","/api/v1/me",token,null);assertEquals(200,me.status());assertEquals(role,me.body().path("data").path("role").asText());assertEquals(1,me.body().path("data").path("box_ids").size());
            Reply own=request("GET","/api/v1/boxes/BOX-000"+box+"/nodes",token,null);assertEquals(role.equals("DELIVERY_USER")?403:200,own.status());
            if(own.status()==200){assertEquals(2,own.body().path("data").size());for(JsonNode n:own.body().path("data")){assertFalse(n.path("online").asBoolean());assertTrue(n.path("boot_id").isNull());assertTrue(n.path("last_received_at").isNull());assertFalse(n.has("password"));}}
            assertEquals(403,request("GET","/api/v1/boxes/BOX-000"+(box==1?2:1)+"/nodes",token,null).status());
            assertEquals(403,request("GET","/api/v1/boxes/UNKNOWN/nodes",token,null).status());
        }
    }
    @Test void preflightUsesExplicitConfiguredOrigins()throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me")).header("Origin","http://localhost:5173").header("Access-Control-Request-Method","GET").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertEquals("http://localhost:5173",response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me")).header("Origin","https://untrusted.example").header("Access-Control-Request-Method","GET").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(403,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void authorizationUsesCurrentDatabaseState()throws Exception{
        var u=account("COURIER",1);long id=users.byUsername(u.get("username").asText()).id();String token=login(u);
        db.update("UPDATE users SET role_code='DELIVERY_USER' WHERE id=?",id);
        try{assertEquals(403,request("GET","/api/v1/boxes/BOX-0001/nodes",token,null).status());}finally{db.update("UPDATE users SET role_code='COURIER' WHERE id=?",id);}
        db.update("DELETE FROM user_box_access WHERE user_id=? AND box_id='BOX-0001'",id);
        try{assertEquals(403,request("GET","/api/v1/boxes/BOX-0001/nodes",token,null).status());}finally{db.update("INSERT INTO user_box_access(user_id,box_id,created_at) VALUES(?,'BOX-0001',?)",id,System.currentTimeMillis());}
        db.update("UPDATE users SET enabled=false WHERE id=?",id);
        try{assertEquals(401,request("GET","/api/v1/me",token,null).status());}finally{db.update("UPDATE users SET enabled=true WHERE id=?",id);}
    }
    @Test void failedUnknownAndDisabledLoginAreUniform()throws Exception{
        var u=account("COURIER",1);ObjectNode wrong=json.createObjectNode().put("username",u.get("username").asText()).put("password",UUID.randomUUID().toString());
        Reply a=request("POST","/api/v1/auth/login",null,wrong);wrong.put("username",UUID.randomUUID().toString());Reply b=request("POST","/api/v1/auth/login",null,wrong);
        assertEquals(401,a.status());assertEquals(401,b.status());assertEquals(a.body().path("error"),b.body().path("error"));
        long id=users.byUsername(u.get("username").asText()).id();db.update("UPDATE users SET enabled=false WHERE id=?",id);
        try{wrong.put("username",u.get("username").asText()).put("password",u.get("password").asText());assertEquals(401,request("POST","/api/v1/auth/login",null,wrong).status());}finally{db.update("UPDATE users SET enabled=true WHERE id=?",id);}
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='LOGIN_FAILED'",Integer.class)>=3);
    }
    @Test void invalidJsonTypesDoNotCoerceIntoCredentials()throws Exception{
        assertEquals(400,request("POST","/api/v1/auth/login",null,json.createObjectNode().put("username",123).put("password",456)).status());
        assertEquals(400,request("POST","/api/v1/auth/login",null,json.createArrayNode()).status());
        assertEquals(400,request("POST","/api/v1/auth/login",null,com.fasterxml.jackson.databind.node.NullNode.instance).status());
    }
    @Test void missingMalformedOrUnknownIdentityRejected()throws Exception{
        assertEquals(401,request("GET","/api/v1/me",null,null).status());assertEquals(401,request("GET","/api/v1/me","invalid",null).status());
        assertEquals(401,request("GET","/api/v1/me",tokens.issue(Long.MAX_VALUE).value(),null).status());
        assertEquals(400,request("POST","/api/v1/auth/login",null,json.createObjectNode()).status());
    }
    @ParameterizedTest @ValueSource(strings={"expired","issuer","audience","missing-exp","algorithm"})
    void httpRejectsBadClaims(String condition)throws Exception{
        long id=users.byUsername(account("COURIER",1).get("username").asText()).id();Instant now=Instant.now().minusSeconds(1);
        var claims=JwtClaimsSet.builder().issuer(condition.equals("issuer")?"other":"smartbox").audience(List.of(condition.equals("audience")?"other":"smartbox-web")).subject(Long.toString(id)).issuedAt(condition.equals("expired")?now.minusSeconds(1800):now);
        if(!condition.equals("missing-exp"))claims.expiresAt(condition.equals("expired")?now:now.plusSeconds(1800));
        var key=condition.equals("algorithm")?new javax.crypto.spec.SecretKeySpec(new byte[64],"HmacSHA384"):settings.key();
        String token=new NimbusJwtEncoder(new ImmutableSecret<>(key)).encode(JwtEncoderParameters.from(JwsHeader.with(condition.equals("algorithm")?MacAlgorithm.HS384:MacAlgorithm.HS256).build(),claims.build())).getTokenValue();
        assertEquals(401,request("GET","/api/v1/me",token,null).status());
    }
    @Test void auditSurvivesBusinessRollback(){
        long user=users.byUsername(account("COURIER",1).get("username").asText()).id();String target="BOX-0001";
        int before=db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='ROLLBACK_PROBE'",Integer.class);
        new TransactionTemplate(manager).executeWithoutResult(s->{audit.record(user,null,target,"ROLLBACK_PROBE","DENIED");s.setRollbackOnly();});
        assertEquals(before+1,db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='ROLLBACK_PROBE'",Integer.class));
    }
    void event(long seq,String uptime){
        db.update("INSERT INTO events(box_id,node_id,boot_id,event_seq,ts_src,uptime_ms,received_at,created_at,type,src,level,replayed,data,raw_event,content_hash) VALUES('BOX-0001','BOX-0001-ENV',4294967295,?,'UNSYNCED',?,?,?,'DEVICE_FAULT','TEST','WARNING',false,'{}','{}',?)",seq,new BigDecimal(uptime),System.currentTimeMillis(),System.currentTimeMillis(),new byte[32]);
    }
    @Test @Transactional void uint64AndEventIdentityBoundaries()throws Exception{
        event(4294967295L,Unsigned64TypeHandler.MAX.toString());assertEquals(Unsigned64TypeHandler.MAX,new BigInteger(db.queryForObject("SELECT CAST(uptime_ms AS CHAR) FROM events WHERE event_seq=4294967295",String.class)));
        assertThrows(DataAccessException.class,()->event(4294967295L,"0"));assertThrows(DataAccessException.class,()->event(0,"0"));assertThrows(DataAccessException.class,()->event(4294967296L,"0"));
        assertThrows(DataAccessException.class,()->event(1,Unsigned64TypeHandler.MAX.add(BigInteger.ONE).toString()));assertThrows(DataAccessException.class,()->event(1,"-1"));
        assertThrows(DataAccessException.class,()->db.update("UPDATE events SET ts_src='SNTP' WHERE event_seq=4294967295"));
        assertThrows(DataAccessException.class,()->db.update("UPDATE events SET box_id='BOX-0002' WHERE event_seq=4294967295"));
        var handler=new Unsigned64TypeHandler();try(var connection=db.getDataSource().getConnection();var s=connection.prepareStatement("SELECT ?")){
            handler.setNonNullParameter(s,1,Unsigned64TypeHandler.MAX,null);try(var result=s.executeQuery()){assertTrue(result.next());assertEquals(Unsigned64TypeHandler.MAX,handler.getNullableResult(result,1));}
        }
    }
    @Test void rawEventAndOutboxRollbackTogether(){
        int events=db.queryForObject("SELECT COUNT(*) FROM events",Integer.class);
        new TransactionTemplate(manager).executeWithoutResult(status->{
            event(10,"0");long id=db.queryForObject("SELECT id FROM events WHERE event_seq=10",Long.class);
            db.update("INSERT INTO processing_outbox(event_id,task_type,next_attempt_at,created_at,updated_at) VALUES(?,'TEST',?,?,?)",id,System.currentTimeMillis(),System.currentTimeMillis(),System.currentTimeMillis());status.setRollbackOnly();
        });
        assertEquals(events,db.queryForObject("SELECT COUNT(*) FROM events",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM processing_outbox",Integer.class));
    }
    @Test @Transactional void capabilityAndTelemetryChecks(){
        assertThrows(DataAccessException.class,()->db.update("UPDATE nodes SET caps=120 WHERE node_id='BOX-0001-ENV'"));
        db.update("INSERT INTO telemetry(box_id,node_id,boot_id,property_seq,ts_src,uptime_ms,received_at,payload,created_at) VALUES('BOX-0001','BOX-0001-ENV',0,4294967295,'UNSYNCED',0,1,'{}',1)");
        assertThrows(DataAccessException.class,()->db.update("UPDATE telemetry SET property_seq=0"));
        assertThrows(DataAccessException.class,()->db.update("UPDATE telemetry SET device_ts=1"));
    }
    void operation(String id,String box,long user){db.update("INSERT INTO command_operations(operation_id,box_id,requested_by,cmd,params,created_at,updated_at) VALUES(?,?,?,'calibrate','{}',1,1)",id,box,user);}
    @Test @Transactional void idempotencyByteAndScopeConstraints(){
        long a=users.byUsername(account("COURIER",1).get("username").asText()).id(),b=users.byUsername(account("LOGISTICS_OPERATOR",1).get("username").asText()).id();
        operation("test-op1","BOX-0001",a);operation("test-op2","BOX-0001",b);operation("test-op3","BOX-0002",a);
        byte[] key="😀".repeat(16).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sql="INSERT INTO idempotency_records(user_id,box_id,idempotency_key,request_hash,operation_id,created_at,expires_at) VALUES(?,?,?,?,?,1,86400001)";
        db.update(sql,a,"BOX-0001",key,new byte[32],"test-op1");
        assertThrows(DataAccessException.class,()->db.update(sql,a,"BOX-0001",key,new byte[32],"test-op1"));
        db.update(sql,b,"BOX-0001",key,new byte[32],"test-op2");db.update(sql,a,"BOX-0002",key,new byte[32],"test-op3");
        assertThrows(DataAccessException.class,()->db.update(sql,a,"BOX-0001",("😀".repeat(16)+"a").getBytes(java.nio.charset.StandardCharsets.UTF_8),new byte[32],"test-op1"));
    }
    @Test @Transactional void incompleteAckRejected(){
        long user=users.byUsername(account("COURIER",1).get("username").asText()).id();operation("test-ack","BOX-0001",user);
        db.update("INSERT INTO node_commands(request_id,operation_id,box_id,node_id,target_caps,issued_at,expires_at,created_at,updated_at) VALUES('test-request','test-ack','BOX-0001','BOX-0001-ENV',64,1,15001,1,1)");
        assertThrows(DataAccessException.class,()->db.update("UPDATE node_commands SET ack_received_at=1,ack_boot_id=0,ack_uptime_ms=0,result='ok',code='completed',actual_state='{}' WHERE request_id='test-request'"));
        db.update("UPDATE node_commands SET ack_received_at=1,ack_boot_id=0,ack_uptime_ms=0,ack_ts_src='UNSYNCED',result='ok',code='completed',actual_state='{}' WHERE request_id='test-request'");
        assertThrows(DataAccessException.class,()->db.update("UPDATE node_commands SET ack_ts=1 WHERE request_id='test-request'"));
        db.update("UPDATE node_commands SET ack_ts_src='SNTP',ack_ts=1,ack_time_uncertainty_ms=0 WHERE request_id='test-request'");
    }
    @Test void checksumMismatchFailsWithoutRepair()throws Exception{
        Path temp=Files.createTempDirectory("smartbox-migration-negative-");
        try{
            try(var files=Files.list(Path.of("src/main/resources/db/migration"))){for(Path p:files.toList())Files.copy(p,temp.resolve(p.getFileName()));}
            Files.writeString(temp.resolve("V1__identity_and_nodes.sql"),"\n-- checksum negative fixture\n",StandardOpenOption.APPEND);
            var invalid=Flyway.configure().dataSource(System.getenv("SMARTBOX_MIGRATION_URL"),System.getenv("SMARTBOX_MIGRATION_USER"),System.getenv("SMARTBOX_MIGRATION_PASSWORD")).locations("filesystem:"+temp).cleanDisabled(true).load();
            assertThrows(org.flywaydb.core.api.exception.FlywayValidateException.class,invalid::validate);flyway.validate();
        }finally{if(!temp.getFileName().toString().startsWith("smartbox-migration-negative-"))throw new IllegalStateException();try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
