package cn.smartbox.live;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;

class MqttTransportTest {
    @Test void cloudRequiresTlsDnsEndpoint() {
        MqttTransport.validate("cloud","ssl://example.emqxsl.cn:8883");
        for(String endpoint:new String[]{"tcp://example.com:1883","ssl://example.com:1883","ssl://user:secret@example.com:8883","ssl://example.com:8883/path","ssl://example.com:8883?skip=true"})
            assertThrows(IllegalArgumentException.class,()->MqttTransport.validate("cloud",endpoint));
    }
    @Test void localCannotSilentlyBecomeRemote() {
        MqttTransport.validate("local","tcp://127.0.0.1:1883");
        assertThrows(IllegalArgumentException.class,()->MqttTransport.validate("local","tcp://example.com:1883"));
        assertThrows(IllegalArgumentException.class,()->MqttTransport.validate("unknown","tcp://127.0.0.1:1883"));
    }
    @Test void badOrMissingTrustAnchorFails() {
        assertThrows(Exception.class,()->MqttTransport.trust(Path.of("missing-ca.pem")));
    }
    @Test void failureDiagnosticsDoNotIncludeRawExceptionText() {
        assertEquals("TLS",MqttGateway.failureCategory(new RuntimeException(new javax.net.ssl.SSLException("secret"))));
        assertEquals("DNS",MqttGateway.failureCategory(new java.net.UnknownHostException("secret")));
        assertEquals("NETWORK",MqttGateway.failureCategory(new java.net.SocketTimeoutException("secret")));
    }
}
