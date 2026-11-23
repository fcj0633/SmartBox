package cn.smartbox.enterprise;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.smartbox.common.ApiException;
import cn.smartbox.live.IngestionService;
import cn.smartbox.live.MqttGateway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Produces controlled DEMO events through the existing ingestion/outbox/rules path.
 * It never writes alarms directly and never touches REAL boxes.
 */
@Service
public class DemoScenarioService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final IngestionService ingestion;
    private final Clock clock;

    public DemoScenarioService(JdbcTemplate db, ObjectMapper json, IngestionService ingestion, Clock clock) {
        this.db = db; this.json = json; this.ingestion = ingestion; this.clock = clock;
    }

    public void apply(String boxId, String scenario) {
        String envNode = node(boxId, "ENV");
        String poseNode = node(boxId, "POSE");
        switch (scenario) {
            case "normal" -> {
                if (hasActiveEnvAlarm(boxId)) {
                    requireNode(envNode);
                    emit(boxId, envNode, "ENV", "ENV_RECOVERED", "INFO", recoveredData());
                }
            }
            case "env_high" -> {
                requireNode(envNode);
                emit(boxId, envNode, "ENV", "ENV_HIGH", "WARNING", envHighData());
            }
            case "impact" -> {
                requireNode(poseNode);
                emit(boxId, poseNode, "POSE", "IMPACT", "CRITICAL", impactData());
            }
            case "offline", "stale" -> { }
            default -> throw new ApiException(400, "INVALID_SCENARIO", "模拟场景不存在");
        }
    }

    private String node(String boxId, String role) {
        var rows = db.queryForList("SELECT node_id FROM nodes WHERE box_id=? AND role=? ORDER BY node_id LIMIT 1", boxId, role);
        return rows.isEmpty() ? null : rows.getFirst().get("node_id").toString();
    }

    private static void requireNode(String node) {
        if (node == null) throw new ApiException(409, "FACT_GENERATION_FAILED", "模拟箱体缺少对应设备节点");
    }

    private boolean hasActiveEnvAlarm(String boxId) {
        Long count = db.queryForObject("SELECT COUNT(*) FROM alarms WHERE box_id=? AND active=1 " +
            "AND alarm_key IN ('env.temp.high','env.temp.low','env.humi.high','env.humi.low')", Long.class, boxId);
        return count != null && count > 0;
    }

    private void emit(String boxId, String node, String src, String type, String level, ObjectNode data) {
        long now = clock.millis();
        long uptime = Math.max(1000, now % 86_400_000L);
        Long seq = db.queryForObject("SELECT COALESCE(MAX(event_seq),0)+1 FROM events WHERE node_id=? AND boot_id=1", Long.class, node);
        ObjectNode root = json.createObjectNode();
        root.put("v", 1); root.put("box_id", boxId); root.put("node_id", node); root.put("boot_id", 1);
        root.put("ts", now); root.put("ts_src", "SNTP"); root.put("uptime_ms", uptime);
        root.put("time_uncertainty_ms", 100);
        ArrayNode events = root.putArray("events");
        ObjectNode event = events.addObject();
        event.put("event_seq", seq == null ? 1 : seq);
        event.put("ts", now); event.put("ts_src", "SNTP"); event.put("uptime_ms", uptime);
        event.put("time_uncertainty_ms", 100);
        event.put("type", type); event.put("src", src); event.put("level", level);
        event.set("data", data); event.put("replayed", false);
        try {
            byte[] payload = json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
            ingestion.accept(new MqttGateway.Packet("box/" + boxId + "/" + node + "/event", payload, 1, false));
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(DemoScenarioService.class)
                .warn("Demo scenario event rejected ({})", error.getClass().getSimpleName());
            throw new ApiException(409, "FACT_GENERATION_FAILED", "模拟场景事实生成失败");
        }
    }

    private ObjectNode recoveredData() {
        ObjectNode data = json.createObjectNode();
        data.put("metric", "temp_c"); data.put("value", 4.8); data.put("threshold", 8.0);
        data.put("profile_id", "SIM-DEFAULT");
        return data;
    }

    private ObjectNode envHighData() {
        ObjectNode data = json.createObjectNode();
        data.put("metric", "temp_c"); data.put("value", 9.2); data.put("threshold", 8.0);
        data.put("profile_id", "SIM-DEFAULT");
        return data;
    }

    private ObjectNode impactData() {
        ObjectNode data = json.createObjectNode();
        data.put("peak_g", 0.42); data.put("duration_ms", 120); data.put("quality", "VALID");
        return data;
    }
}
