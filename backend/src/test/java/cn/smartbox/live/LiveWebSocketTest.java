package cn.smartbox.live;
import cn.smartbox.auth.*;
import java.time.Clock;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveWebSocketTest {
    @Test void readOnlySubscriberSurvivesAnOperatorsCommandUpdate()throws Exception{
        AuthMapper users=mock(AuthMapper.class);when(users.byId(1L)).thenReturn(new UserAccount(1L,"viewer",null,"LOGISTICS_VIEWER",true));
        QueryService query=mock(QueryService.class);CommandService commands=mock(CommandService.class);LiveStore db=mock(LiveStore.class);
        ContractValidator validator=mock(ContractValidator.class);var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        when(validator.parse(any())).thenReturn(mapper.readTree("{\"v\":1,\"type\":\"subscribe\",\"box_ids\":[\"BOX-0001\"]}"));
        when(validator.hash(any())).thenReturn(new byte[]{1});when(validator.text(any())).thenReturn("{}");
        when(query.snapshot(any(),any())).thenReturn(mapper.createObjectNode());when(query.nodes(any(),any())).thenReturn(List.of());
        when(db.rows(startsWith("SELECT operation_id"),any(),any())).thenReturn(List.of(Map.of("operation_id","other-operation","updated_at",1L,"cmd","output")));
        when(commands.operation(any(),any(),any())).thenThrow(cn.smartbox.common.ApiException.denied());
        Clock clock=Clock.systemUTC();LiveWebSocket handler=new LiveWebSocket(users,null,null,query,commands,validator,clock,mock(cn.smartbox.authorization.BoxAccessService.class),db);
        WebSocketSession socket=mock(WebSocketSession.class);AtomicBoolean open=new AtomicBoolean(true);CountDownLatch sent=new CountDownLatch(1),closed=new CountDownLatch(1);
        when(socket.getId()).thenReturn("viewer");when(socket.isOpen()).thenAnswer(i->open.get());
        when(socket.getAttributes()).thenReturn(Map.of("ticket",new LiveWebSocket.Ticket(1L,clock.millis()+30000,clock.millis()+1800000)));
        doAnswer(i->{sent.countDown();return null;}).when(socket).sendMessage(any());
        doAnswer(i->{open.set(false);closed.countDown();return null;}).when(socket).close(any());
        try{
            handler.afterConnectionEstablished(socket);handler.handleTextMessage(socket,new TextMessage("subscription"));handler.push();
            assertTrue(sent.await(2,TimeUnit.SECONDS));assertFalse(closed.await(200,TimeUnit.MILLISECONDS));assertTrue(open.get());
            verify(commands,never()).operation(any(),any(),any());
        }finally{handler.shutdown();}
    }
    @Test void mqttQueueIsBoundedNonblockingAndOwnsItsBytes(){
        MqttGateway gateway=new MqttGateway(new org.springframework.mock.env.MockEnvironment(),mock(LiveStore.class));
        byte[] bytes={1};assertTrue(gateway.enqueue("test",bytes,1,false));bytes[0]=9;
        for(int i=1;i<256;i++)assertTrue(gateway.enqueue("test",bytes,1,false));
        assertFalse(gateway.enqueue("test",bytes,1,false));assertEquals(1,gateway.dropped.get());
        assertEquals(1,gateway.poll().payload()[0]);for(int i=1;i<256;i++)assertNotNull(gateway.poll());assertNull(gateway.poll());
    }
    @Test void oversizedMessageDoesNotConsumeQueue(){
        MqttGateway gateway=new MqttGateway(new org.springframework.mock.env.MockEnvironment(),mock(LiveStore.class));
        assertFalse(gateway.enqueue("test",new byte[1537],1,false));assertNull(gateway.poll());assertEquals(1,gateway.dropped.get());
    }
    @Test void blockedConsumerCannotGrowBeyondQueueAndIsClosed()throws Exception{
        AuthMapper users=mock(AuthMapper.class);when(users.byId(1L)).thenReturn(new UserAccount(1L,"slow",null,"LOGISTICS_OPERATOR",true));
        ContractValidator validator=mock(ContractValidator.class);when(validator.parse(any())).thenThrow(new IllegalArgumentException());
        when(validator.text(any())).thenReturn("{}");
        Clock clock=Clock.systemUTC();LiveWebSocket handler=new LiveWebSocket(users,null,null,null,null,validator,clock,null,null);
        WebSocketSession session=mock(WebSocketSession.class);AtomicBoolean open=new AtomicBoolean(true);
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
        when(session.getId()).thenReturn("slow");when(session.isOpen()).thenAnswer(i->open.get());
        when(session.getAttributes()).thenReturn(Map.of("ticket",new LiveWebSocket.Ticket(1L,clock.millis()+30000,clock.millis()+1800000)));
        doAnswer(i->{blocked.countDown();release.await(3,TimeUnit.SECONDS);return null;}).when(session).sendMessage(any());
        doAnswer(i->{open.set(false);release.countDown();closed.countDown();return null;}).when(session).close(any());
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session,new TextMessage("bad"));assertTrue(blocked.await(2,TimeUnit.SECONDS));
        for(int i=0;i<65;i++)handler.handleTextMessage(session,new TextMessage("bad"));
        assertTrue(closed.await(2,TimeUnit.SECONDS));assertFalse(open.get());handler.shutdown();
    }
}
