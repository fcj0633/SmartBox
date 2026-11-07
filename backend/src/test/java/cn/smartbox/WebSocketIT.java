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
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class WebSocketIT {
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
        org.mockito.Mockito.when(clock.millis()).thenAnswer(i->now.get());
        org.mockito.Mockito.when(clock.instant()).thenAnswer(i->Instant.ofEpochMilli(now.get()));
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
    @org.springframework.test.context.bean.override.mockito.MockitoBean Clock clock;
    final java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    @BeforeEach void resetClock(){now.set(System.currentTimeMillis());org.mockito.Mockito.when(clock.millis()).thenAnswer(i->now.get());org.mockito.Mockito.when(clock.instant()).thenAnswer(i->Instant.ofEpochMilli(now.get()));}
    static class Listener implements WebSocket.Listener {
        final java.util.concurrent.BlockingQueue<String> messages=new java.util.concurrent.LinkedBlockingQueue<>();
        final java.util.concurrent.CountDownLatch closed=new java.util.concurrent.CountDownLatch(1);
        final StringBuilder buffer=new StringBuilder();
        public void onOpen(WebSocket socket){socket.request(1);}
        public java.util.concurrent.CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){buffer.append(data);if(last){messages.add(buffer.toString());buffer.setLength(0);}socket.request(1);return null;}
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket socket,int code,String reason){closed.countDown();return null;}
        public void onError(WebSocket socket,Throwable error){closed.countDown();}
    }
    String ticket(String token)throws Exception{return request("POST","/api/v1/ws-ticket",token,null).body().path("data").path("ticket").asText();}
    WebSocket connect(String ticket,Listener listener){return client.newWebSocketBuilder().header("Origin","http://localhost:5173").buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws?ticket="+ticket),listener).join();}
    @Test void ticketExpiresAtThirtySeconds()throws Exception{
        String token=login(account("LOGISTICS_OPERATOR",1)),ticket=ticket(token);now.addAndGet(30001);
        assertThrows(java.util.concurrent.CompletionException.class,()->connect(ticket,new Listener()));
    }
    @Test void jwtExpiryClosesRealConnection()throws Exception{
        String token=login(account("LOGISTICS_OPERATOR",1));Listener listener=new Listener();WebSocket socket=connect(ticket(token),listener);
        socket.sendText("{\"v\":1,\"type\":\"subscribe\",\"box_ids\":[\"BOX-0001\"]}",true).join();assertNotNull(listener.messages.poll(5,java.util.concurrent.TimeUnit.SECONDS));
        now.addAndGet(1800001);assertTrue(listener.closed.await(5,java.util.concurrent.TimeUnit.SECONDS));socket.abort();
    }
    @Test void permissionRevocationClosesRealConnection()throws Exception{
        JsonNode account=account("LOGISTICS_OPERATOR",1);String token=login(account);long id=users.byUsername(account.path("username").asText()).id();
        Listener listener=new Listener();WebSocket socket=connect(ticket(token),listener);
        socket.sendText("{\"v\":1,\"type\":\"subscribe\",\"box_ids\":[\"BOX-0001\"]}",true).join();assertNotNull(listener.messages.poll(5,java.util.concurrent.TimeUnit.SECONDS));
        db.update("DELETE FROM user_box_access WHERE user_id=? AND box_id='BOX-0001'",id);
        try{assertTrue(listener.closed.await(5,java.util.concurrent.TimeUnit.SECONDS));}
        finally{db.update("INSERT INTO user_box_access(user_id,box_id,created_at) VALUES(?,'BOX-0001',?)",id,System.currentTimeMillis());socket.abort();}
    }
    @Test void simultaneousTicketUseHasOnlyOneWinner()throws Exception{
        String token=login(account("LOGISTICS_OPERATOR",1)),ticket=ticket(token);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            java.util.concurrent.Callable<WebSocket> action=()->{try{return connect(ticket,new Listener());}catch(java.util.concurrent.CompletionException rejected){return null;}};
            var results=pool.invokeAll(List.of(action,action));int winners=0;for(var result:results){WebSocket socket=result.get();if(socket!=null){winners++;socket.abort();}}assertEquals(1,winners);
        }finally{pool.shutdownNow();}
    }
}
