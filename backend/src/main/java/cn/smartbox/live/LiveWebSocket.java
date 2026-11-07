package cn.smartbox.live;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import cn.smartbox.auth.*;
import cn.smartbox.config.LocalSettings;
import cn.smartbox.common.ApiException;
import cn.smartbox.authorization.*;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;

@Component
public class LiveWebSocket extends TextWebSocketHandler implements HandshakeInterceptor {
    record Ticket(long user,long expires,long jwtExpires){}
    private final Map<String,Ticket> tickets=new HashMap<>();
    private final ConcurrentMap<String,Client> clients=new ConcurrentHashMap<>();
    private final SecureRandom random=new SecureRandom();
    private final AuthMapper users;private final TokenService tokens;private final LocalSettings settings;private final QueryService query;private final CommandService commands;private final ContractValidator json;private final Clock clock;private final BoxAccessService access;private final LiveStore db;
    public LiveWebSocket(AuthMapper users,TokenService tokens,LocalSettings settings,QueryService query,CommandService commands,ContractValidator json,Clock clock,BoxAccessService access,LiveStore db){this.users=users;this.tokens=tokens;this.settings=settings;this.query=query;this.commands=commands;this.json=json;this.clock=clock;this.access=access;this.db=db;}
    static final class Client {
        final WebSocketSession session;final Ticket ticket;final Set<String> boxes=ConcurrentHashMap.newKeySet();
        final ArrayBlockingQueue<Notice> queue=new ArrayBlockingQueue<>(64);
        final Set<String> sent=new HashSet<>();final Map<String,String> snapshots=new HashMap<>();
        volatile long pong;long ping;long since;
        volatile long sendingSince;
        Client(WebSocketSession session,Ticket ticket,long now){this.session=session;this.ticket=ticket;this.pong=now;this.ping=now;this.since=now;}
    }
    record Notice(String type,String box,String identity,JsonNode data){}
    public synchronized Map<String,Object> ticket(CurrentUser user,String authorization){
        long now=clock.millis();tickets.entrySet().removeIf(e->e.getValue().expires<=now);
        if(tickets.size()>=1024)throw new ApiException(503,"SERVICE_UNAVAILABLE","票据容量暂满");
        if(!authorization.startsWith("Bearer "))throw ApiException.denied();
        long expires=tokens.decoder().decode(authorization.substring(7)).getExpiresAt().toEpochMilli();
        byte[] bytes=new byte[32];random.nextBytes(bytes);String value=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(digest(value),new Ticket(user.id(),now+30000,expires));return Map.of("ticket",value,"expires_at",now+30000);
    }
    private String digest(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private CurrentUser current(Ticket ticket){
        if(clock.millis()>=ticket.jwtExpires)throw ApiException.denied();
        UserAccount user=users.byId(ticket.user);if(user==null||!Boolean.TRUE.equals(user.enabled()))throw ApiException.denied();
        return new CurrentUser(user.id(),user.username(),user.roleCode());
    }
    @Override public synchronized boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes){
        try{
            if(!settings.origins().contains(request.getHeaders().getOrigin())){response.setStatusCode(HttpStatus.FORBIDDEN);return false;}
            String raw=request.getURI().getRawQuery();if(raw==null||!raw.startsWith("ticket=")||raw.contains("&"))throw ApiException.denied();
            String key=digest(URLDecoder.decode(raw.substring(7),StandardCharsets.UTF_8));Ticket ticket=tickets.get(key);
            if(ticket==null||clock.millis()>=ticket.expires)throw ApiException.denied();current(ticket);
            if(clients.size()>=64){response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);return false;}
            tickets.remove(key);attributes.put("ticket",ticket);return true;
        }catch(Exception error){response.setStatusCode(HttpStatus.UNAUTHORIZED);return false;}
    }
    @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception){}
    @Override public void afterConnectionEstablished(WebSocketSession session){
        if(session instanceof org.springframework.web.socket.adapter.standard.StandardWebSocketSession standard)
            standard.getNativeSession().getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT",2000L);
        session.setTextMessageSizeLimit(8192);Client client=new Client(session,(Ticket)session.getAttributes().get("ticket"),clock.millis());clients.put(session.getId(),client);
        Thread.ofVirtual().name("smartbox-ws-send").start(()->send(client));
    }
    private void offer(Client client,Notice notice){if(!client.queue.offer(notice))close(client,CloseStatus.SESSION_NOT_RELIABLE);}
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message){
        Client client=clients.get(session.getId());if(client==null)return;
        try{
            JsonNode input=json.parse(message.asBytes());json.validate("WsSubscribe",input);CurrentUser user=current(client.ticket);
            Set<String> boxes=new HashSet<>();for(JsonNode box:input.path("box_ids")){access.require(user,box.asText(),PermissionPolicy.Action.READ_BUSINESS);boxes.add(box.asText());}
            if(input.path("type").asText().equals("subscribe"))client.boxes.addAll(boxes);else client.boxes.removeAll(boxes);
        }catch(Exception error){
            ObjectNode body=JsonNodeFactory.instance.objectNode();body.put("v",1);body.put("type","error");body.put("request_id",UUID.randomUUID().toString());ObjectNode detail=body.putObject("error");detail.put("code","FORBIDDEN");detail.put("message","订阅无效或无权访问");offer(client,new Notice("error",null,null,body));
        }
    }
    @Override protected void handlePongMessage(WebSocketSession session,PongMessage message){Client client=clients.get(session.getId());if(client!=null)client.pong=clock.millis();}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){clients.remove(session.getId());}
    private void close(Client client,CloseStatus status){clients.remove(client.session.getId());try{client.session.close(status);}catch(Exception ignored){}}
    private void send(Client client){
        while(client.session.isOpen()&&clients.containsKey(client.session.getId())){
            try{
                Notice notice=client.queue.poll(500,TimeUnit.MILLISECONDS);if(notice==null)continue;
                CurrentUser user=current(client.ticket);
                if(notice.box!=null){
                    access.require(user,notice.box,PermissionPolicy.Action.READ_BUSINESS);
                    if(!client.boxes.contains(notice.box))continue;
                    if(user.role().equals("DELIVERY_USER")&&Set.of("command_status","node_status").contains(notice.type))continue;
                }
                if(notice.type.equals("ping")){client.sendingSince=clock.millis();client.session.sendMessage(new PingMessage());client.sendingSince=0;continue;}
                JsonNode data=notice.data;
                // Re-project immediately before sending, so queued old permissions never leak fields.
                if(notice.type.equals("snapshot"))data=query.snapshot(user,notice.box);
                else if(notice.type.equals("node_status"))data=query.nodes(user,notice.box).stream().filter(n->n.path("node_id").asText().equals(notice.identity)).findFirst().orElseThrow();
                else if(notice.type.equals("command_status"))data=commands.operation(user,notice.box,notice.identity);
                else if(notice.type.equals("box_event")&&user.role().equals("DELIVERY_USER")&&!Set.of("DOOR_CONCLUSION","ALARM").contains(data.path("type").asText()))continue;
                if(notice.type.equals("error")){client.sendingSince=clock.millis();client.session.sendMessage(new TextMessage(json.text(data)));client.sendingSince=0;continue;}
                ObjectNode message=JsonNodeFactory.instance.objectNode();message.put("v",1);message.put("type",notice.type);message.put("box_id",notice.box);message.put("message_id",UUID.randomUUID().toString());message.put("ts",clock.millis());message.set("data",data);
                client.sendingSince=clock.millis();client.session.sendMessage(new TextMessage(json.text(message)));
                client.sendingSince=0;
            }catch(Exception error){close(client,CloseStatus.POLICY_VIOLATION);}
        }
    }
    @Scheduled(fixedDelay=1000)
    public void push(){
        long now=clock.millis();
        for(Client client:clients.values())try{
            CurrentUser user=current(client.ticket);
            if(client.sendingSince>0&&now-client.sendingSince>2000){close(client,CloseStatus.SESSION_NOT_RELIABLE);continue;}
            if(now-client.pong>60000){close(client,CloseStatus.SESSION_NOT_RELIABLE);continue;}
            if(now-client.ping>=30000){offer(client,new Notice("ping",null,null,null));client.ping=now;}
            for(String box:new HashSet<>(client.boxes)){
                access.require(user,box,PermissionPolicy.Action.READ_BUSINESS);
                JsonNode snapshot=query.snapshot(user,box);String fingerprint=HexFormat.of().formatHex(json.hash(snapshot));
                if(!fingerprint.equals(client.snapshots.put(box,fingerprint)))offer(client,new Notice("snapshot",box,null,snapshot));
                if(!user.role().equals("DELIVERY_USER")){
                    for(JsonNode node:query.nodes(user,box))offer(client,new Notice("node_status",box,node.path("node_id").asText(),null));
                    for(var operation:db.rows("SELECT operation_id,updated_at,cmd FROM command_operations WHERE box_id=? AND updated_at>=?",box,client.since)){
                        if(user.role().equals("COURIER")&&!operation.get("cmd").equals("sentry"))continue;
                        String key="op:"+operation.get("operation_id")+":"+operation.get("updated_at");
                        if(client.sent.add(key))offer(client,new Notice("command_status",box,operation.get("operation_id").toString(),null));
                    }
                }
                for(var row:db.rows("SELECT event_id,payload FROM event_views WHERE box_id=? AND received_at>=? ORDER BY event_id LIMIT 100",box,client.since)){
                    if(client.sent.add("ev:"+row.get("event_id")))offer(client,new Notice("box_event",box,null,json.json(row.get("payload"))));
                }
            }
            // Bounded duplicate window; WS is best effort and REST is the recovery path.
            if(client.sent.size()>2048){client.sent.clear();client.since=now;}
        }catch(Exception error){close(client,CloseStatus.POLICY_VIOLATION);}
    }
    @PreDestroy public void shutdown(){clients.values().forEach(c->close(c,CloseStatus.GOING_AWAY));}
}
