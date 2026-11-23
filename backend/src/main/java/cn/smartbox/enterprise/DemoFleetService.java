package cn.smartbox.enterprise;

import java.time.Clock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.smartbox.asset.DemoAssetConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * DEMO-only snapshot producer for the seeded SIMULATED boxes. It is disabled by default,
 * never touches REAL boxes and does not claim to be MQTT/device telemetry.
 */
@Service
@ConditionalOnProperty(name = "smartbox.demo.fleet", havingValue = "true")
public class DemoFleetService {
    private final JdbcTemplate db;
    private final DemoAssetConfig config;
    private final ObjectMapper json;
    private final Clock clock;

    public DemoFleetService(JdbcTemplate db, DemoAssetConfig config, ObjectMapper json, Clock clock) {
        this.db = db; this.config = config; this.json = json; this.clock = clock;
    }

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        JsonNode seed;
        try {
            seed = config.seed();
        } catch (RuntimeException unavailable) {
            return;
        }
        JsonNode boxes = seed.path("simulated_boxes");
        if (!boxes.isArray()) return;
        long now = clock.millis();
        for (JsonNode box : boxes) {
            String boxId = box.path("box_id").asText("");
            if (boxId.isBlank() || !"SIMULATED".equals(source(boxId))) continue;
            String scenario = box.path("scenario").asText("normal");
            String envNode = box.path("env_node_id").asText("");
            String poseNode = box.path("pose_node_id").asText("");
            if ("offline".equals(scenario)) {
                markOffline(envNode);
                markOffline(poseNode);
                continue;
            }
            if (!envNode.isBlank()) {
                runtime(envNode, now);
                latest(boxId, envNode, "ENV", 71, now, envPayload(boxId, envNode, scenario, now));
            }
            if (!poseNode.isBlank()) {
                runtime(poseNode, now);
                latest(boxId, poseNode, "POSE", 120, now, posePayload(boxId, poseNode, scenario, now));
            }
        }
    }

    private String source(String boxId) {
        var rows = db.queryForList("SELECT asset_source FROM boxes WHERE box_id=?", boxId);
        return rows.isEmpty() ? null : rows.getFirst().get("asset_source").toString();
    }

    private void runtime(String node, long now) {
        long uptime = Math.max(1000, now % 86_400_000L);
        db.update("INSERT INTO node_runtime(node_id,boot_id,online,last_seen,uptime_ms,ts_src,heartbeat,actual_state) " +
                "VALUES(?,1,1,?,?, 'SNTP',NULL,NULL) ON DUPLICATE KEY UPDATE boot_id=1,online=1,last_seen=VALUES(last_seen)," +
                "uptime_ms=VALUES(uptime_ms),ts_src='SNTP'", node, now, uptime);
        db.update("UPDATE nodes SET current_boot_id=1,last_received_at=?,updated_at=? WHERE node_id=?", now, now, node);
    }

    private void markOffline(String node) {
        if (node == null || node.isBlank()) return;
        db.update("UPDATE node_runtime SET online=0 WHERE node_id=?", node);
    }

    private void latest(String boxId, String node, String role, int caps, long now, ObjectNode payload) {
        long seq = Math.max(1, (now / 1000) % 4_294_967_295L);
        db.update("INSERT INTO node_latest(box_id,node_id,boot_id,property_seq,device_ts,ts_src,uptime_ms," +
                "time_uncertainty_ms,received_at,payload,updated_at) VALUES(?,?,1,?,?, 'SNTP',?,100,?,?,?) " +
                "ON DUPLICATE KEY UPDATE boot_id=1,property_seq=VALUES(property_seq),device_ts=VALUES(device_ts)," +
                "ts_src='SNTP',uptime_ms=VALUES(uptime_ms),time_uncertainty_ms=100,received_at=VALUES(received_at)," +
                "payload=VALUES(payload),updated_at=VALUES(updated_at)",
            boxId, node, seq, now, Math.max(1000, now % 86_400_000L), now, payloadJson(payload), now);
    }

    private ObjectNode envPayload(String boxId, String node, String scenario, long now) {
        double temp = switch (scenario) {
            case "env_high" -> 9.2;
            case "impact" -> 5.1;
            case "completed" -> 4.5;
            default -> 4.8;
        };
        double humi = switch (scenario) {
            case "env_high" -> 62.0;
            case "impact" -> 50.0;
            case "completed" -> 53.0;
            default -> 55.0;
        };
        int lux = "env_high".equals(scenario) ? 280 : "impact".equals(scenario) ? 300 : 320;
        ObjectNode root = base(boxId, node, "ENV", 71, now);
        ObjectNode env = root.putObject("env");
        env.put("temp_c", temp); env.put("humi_pct", humi); env.put("lux", lux);
        env.put("valid", true); env.put("light_on", false); env.put("fan_on", false);
        return root;
    }

    private ObjectNode posePayload(String boxId, String node, String scenario, long now) {
        ObjectNode root = base(boxId, node, "POSE", 120, now);
        ObjectNode pose = root.putObject("pose");
        ArrayNode acc = pose.putArray("acc_g");
        if ("impact".equals(scenario)) {
            acc.add(0.42); acc.add(0.18); acc.add(0.79);
            pose.put("tilt_deg", 28.0); pose.put("state", "TILTED");
        } else {
            acc.add(0.02); acc.add(0.01); acc.add(0.99);
            pose.put("tilt_deg", 2.0); pose.put("state", "NORMAL");
        }
        pose.put("valid", true);
        return root;
    }

    private ObjectNode base(String boxId, String node, String role, int caps, long now) {
        ObjectNode root = json.createObjectNode();
        root.put("v", 1); root.put("box_id", boxId); root.put("node_id", node); root.put("boot_id", 1);
        root.put("ts", now); root.put("ts_src", "SNTP"); root.put("uptime_ms", Math.max(1000, now % 86_400_000L));
        root.put("time_uncertainty_ms", 100); root.put("property_seq", Math.max(1, (now / 1000) % 4_294_967_295L));
        root.put("role", role); root.put("caps", caps);
        return root;
    }

    private String payloadJson(ObjectNode value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Demo payload serialization failed"); }
    }
}
