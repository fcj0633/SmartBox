package cn.smartbox.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fixed remote MQTT configuration. There is intentionally no local-broker fallback. */
@Component
public class MqttSettings {
    private static final String CONFIG = "deploy/demo/mqtt.cloud.json";
    private static final String HOST = "z789ffe0.ala.cn-hangzhou.emqxsl.cn";
    private static final int PORT = 8883;
    private static final String USER = "smartbox_backend";

    private final boolean enabled;
    private final String endpoint;
    private final String user;
    private final String password;
    private final Path ca;
    private final String clientId;

    public MqttSettings(Environment env, ObjectMapper json) {
        String requested = env.getProperty("SMARTBOX_MQTT_ENABLED");
        Path config = resolveConfig();
        if ("false".equalsIgnoreCase(requested)) {
            enabled = false;
            endpoint = ""; user = ""; password = ""; ca = null; clientId = "";
            return;
        }
        if (!Files.isRegularFile(config)) {
            if ("true".equalsIgnoreCase(requested)) throw new IllegalArgumentException("MQTT enabled but " + CONFIG + " is missing");
            enabled = false;
            endpoint = ""; user = ""; password = ""; ca = null; clientId = "";
            return;
        }
        JsonNode root;
        try { root = json.readTree(Files.readString(config)); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid MQTT configuration"); }
        JsonNode broker = root.path("broker");
        JsonNode backend = root.path("accounts").path("backend");
        if (!"cloud".equals(broker.path("mode").asText())
                || !HOST.equals(broker.path("host").asText())
                || broker.path("port").asInt() != PORT
                || !broker.path("tls").asBoolean(false)
                || !USER.equals(backend.path("username").asText())
                || backend.path("password").asText().isBlank()
                || backend.path("password").asText().length() > 512) {
            throw new IllegalArgumentException("MQTT configuration must target the fixed remote TLS broker");
        }
        endpoint = "ssl://" + HOST + ":" + PORT;
        MqttTransport.validate(endpoint);
        Path caPath = Path.of(broker.path("ca_file").asText());
        ca = caPath.isAbsolute() ? caPath : config.getParent().resolve(caPath).normalize();
        try { MqttTransport.trust(ca); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid MQTT CA bundle"); }
        user = USER;
        password = backend.path("password").asText();
        String configuredClientId = env.getProperty("SMARTBOX_MQTT_CLIENT_ID", "smartbox_backend-" + UUID.randomUUID().toString().substring(0, 8)).trim();
        if (configuredClientId.isEmpty() || configuredClientId.length() > 128) throw new IllegalArgumentException("Invalid MQTT client ID");
        clientId = configuredClientId;
        enabled = true;
    }

    private static Path resolveConfig() {
        Path relative = Path.of(CONFIG);
        if (Files.isRegularFile(relative)) return relative.toAbsolutePath().normalize();
        Path parent = Path.of("..").resolve(CONFIG);
        if (Files.isRegularFile(parent)) return parent.toAbsolutePath().normalize();
        return relative.toAbsolutePath().normalize();
    }

    public boolean enabled() { return enabled; }
    public String endpoint() { return endpoint; }
    public String user() { return user; }
    public String password() { return password; }
    public Path ca() { return ca; }
    public String clientId() { return clientId; }
}
