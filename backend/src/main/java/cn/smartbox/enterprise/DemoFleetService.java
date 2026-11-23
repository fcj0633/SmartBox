package cn.smartbox.enterprise;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * DEMO-only snapshot producer for SIMULATED boxes. It reads database scenarios, is disabled by default,
 * never touches REAL boxes and does not claim to be MQTT/device telemetry.
 */
@Service
@ConditionalOnProperty(name = "smartbox.demo.fleet", havingValue = "true")
public class DemoFleetService {
    private static final String BOXES_SQL =
        "SELECT b.box_id,b.demo_scenario," +
        "MAX(CASE WHEN n.role='ENV' THEN n.node_id END) AS env_node," +
        "MAX(CASE WHEN n.role='POSE' THEN n.node_id END) AS pose_node " +
        "FROM boxes b LEFT JOIN nodes n ON n.box_id=b.box_id " +
        "WHERE b.asset_source='SIMULATED' AND b.demo_enabled=1 " +
        "GROUP BY b.box_id,b.demo_scenario ORDER BY b.box_id";
    private static final String BOX_SQL =
        "SELECT b.box_id,b.demo_scenario," +
        "MAX(CASE WHEN n.role='ENV' THEN n.node_id END) AS env_node," +
        "MAX(CASE WHEN n.role='POSE' THEN n.node_id END) AS pose_node " +
        "FROM boxes b LEFT JOIN nodes n ON n.box_id=b.box_id " +
        "WHERE b.asset_source='SIMULATED' AND b.demo_enabled=1 AND b.box_id=? " +
        "GROUP BY b.box_id,b.demo_scenario";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean scheduled;

    public DemoFleetService(JdbcTemplate db, ObjectMapper json, Clock clock,
                            @org.springframework.beans.factory.annotation.Value("${smartbox.demo.fleet.tick:true}") boolean scheduled) {
        this.db = db; this.json = json; this.clock = clock; this.scheduled = scheduled;
    }

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        if (!scheduled) return;
        long now = clock.millis();
        for (Map<String, Object> box : db.queryForList(BOXES_SQL)) apply(box, now);
    }

    /** Applies one box immediately after scenario/enable changes; the next tick is idempotent. */
    public void refresh(String boxId) {
        for (Map<String, Object> box : db.queryForList(BOX_SQL, boxId)) apply(box, clock.millis());
    }

    /** Marks both nodes offline immediately when a simulated asset is disabled. */
    public void markOffline(String boxId) {
        db.update("UPDATE node_runtime r JOIN nodes n ON n.node_id=r.node_id SET r.online=0 WHERE n.box_id=?", boxId);
    }

    private void apply(Map<String, Object> box, long now) {
        String boxId = text(box, "box_id");
        String scenario = text(box, "demo_scenario");
        if (scenario == null || scenario.isBlank()) scenario = "normal";
        String envNode = text(box, "env_node"), poseNode = text(box, "pose_node");
        if ("offline".equals(scenario)) {
            markOffline(boxId);
            return;
        }
        if (envNode != null) {
            runtime(envNode, now);
            if (!"stale".equals(scenario)) latest(boxId, envNode, 71, now, envPayload(boxId, envNode, scenario, now));
        }
        if (poseNode != null) {
            runtime(poseNode, now);
            if (!"stale".equals(scenario)) latest(boxId, poseNode, 120, now, posePayload(boxId, poseNode, scenario, now));
        }
    }

    private void runtime(String node, long now) {
        long uptime = Math.max(1000, now % 86_400_000L);
        db.update("INSERT INTO node_runtime(node_id,boot_id,online,last_seen,uptime_ms,ts_src,heartbeat,actual_state) " +
                "VALUES(?,1,1,?,?, 'SNTP',NULL,NULL) ON DUPLICATE KEY UPDATE boot_id=1,online=1,last_seen=VALUES(last_seen)," +
                "uptime_ms=VALUES(uptime_ms),ts_src='SNTP'", node, now, uptime);
        db.update("UPDATE nodes SET current_boot_id=1,last_received_at=?,updated_at=? WHERE node_id=?", now, now, node);
    }

    private void latest(String boxId, String node, int caps, long now, ObjectNode payload) {
        long seq = Math.max(1, (now / 1000) % 4_294_967_295L);
        long uptime = Math.max(1000, now % 86_400_000L);
        String body = payloadJson(payload);
        db.update("INSERT INTO node_latest(box_id,node_id,boot_id,property_seq,device_ts,ts_src,uptime_ms," +
                "time_uncertainty_ms,received_at,payload,updated_at) VALUES(?,?,1,?,?, 'SNTP',?,100,?,?,?) " +
                "ON DUPLICATE KEY UPDATE boot_id=1,property_seq=VALUES(property_seq),device_ts=VALUES(device_ts)," +
                "ts_src='SNTP',uptime_ms=VALUES(uptime_ms),time_uncertainty_ms=100,received_at=VALUES(received_at)," +
                "payload=VALUES(payload),updated_at=VALUES(updated_at)",
            boxId, node, seq, now, uptime, now, body, now);
        // Simulated history uses the same telemetry shape as MQTT ingestion so detail charts are server-backed.
        db.update("INSERT INTO telemetry(box_id,node_id,boot_id,property_seq,device_ts,ts_src,uptime_ms," +
                "time_uncertainty_ms,received_at,payload,created_at) VALUES(?,?,1,?,?, 'SNTP',?,100,?,?,?) " +
                "ON DUPLICATE KEY UPDATE device_ts=VALUES(device_ts),ts_src='SNTP',uptime_ms=VALUES(uptime_ms)," +
                "time_uncertainty_ms=100,received_at=VALUES(received_at),payload=VALUES(payload)",
            boxId, node, seq, now, uptime, now, body, now);
    }

    private ObjectNode envPayload(String boxId, String node, String scenario, long now) {
        double temp = switch (scenario) {
            case "env_high" -> 9.2;
            case "impact" -> 5.1;
            default -> 4.8;
        };
        double humi = switch (scenario) {
            case "env_high" -> 62.0;
            case "impact" -> 50.0;
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

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }
}
