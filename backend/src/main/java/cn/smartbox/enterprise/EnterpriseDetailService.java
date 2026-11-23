package cn.smartbox.enterprise;

import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.common.ApiException;
import cn.smartbox.enterprise.EnterpriseViews.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only projections for the eight detail tabs. Scope is always the enterprise ownership. */
@Service
public class EnterpriseDetailService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final EnterpriseReadService read;

    public EnterpriseDetailService(JdbcTemplate db, ObjectMapper json, Clock clock, EnterpriseReadService read) {
        this.db = db; this.json = json; this.clock = clock; this.read = read;
    }

    public List<SamplePoint> samples(CurrentUser user, String boxId, String kind, int hours) {
        read.box(user, boxId);
        String selected = kind == null ? "env" : kind;
        if (!Set.of("env", "pose").contains(selected)) throw new ApiException(400, "VALIDATION_ERROR", "采样类型不支持");
        if (!Set.of(1, 6, 24).contains(hours)) throw new ApiException(400, "VALIDATION_ERROR", "时间窗口只能是 1/6/24 小时");
        long from = clock.millis() - hours * 3_600_000L;
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT id,device_ts,received_at,payload FROM telemetry WHERE box_id=? AND received_at>=? " +
            "ORDER BY received_at DESC,id DESC LIMIT 200", boxId, from);
        List<SamplePoint> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            JsonNode payload = parse(row.get("payload"));
            JsonNode value = payload.path(selected);
            if (!value.isObject()) continue;
            Long observed = payload.path("ts").isNumber() ? payload.path("ts").asLong() : number(row, "received_at");
            boolean valid = value.path("valid").asBoolean(false);
            List<Double> acc = new ArrayList<>();
            if (value.path("acc_g").isArray()) value.path("acc_g").forEach(item -> {
                if (item.isNumber()) acc.add(item.asDouble());
            });
            result.add(new SamplePoint(number(row, "id"), observed, number(row, "received_at"), valid,
                value.path("temp_c").isNumber() ? value.path("temp_c").asDouble() : null,
                value.path("humi_pct").isNumber() ? value.path("humi_pct").asDouble() : null,
                value.path("lux").isNumber() ? value.path("lux").asDouble() : null,
                value.path("state").isTextual() ? value.path("state").asText() : null,
                value.path("tilt_deg").isNumber() ? value.path("tilt_deg").asDouble() : null,
                List.copyOf(acc), payload.path("ts_src").asText("UNKNOWN")));
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    public List<Event> events(CurrentUser user, String boxId, int limit) {
        return read.boxEvents(user, boxId, limit);
    }

    public List<AlarmRow> alarms(CurrentUser user, String boxId) {
        read.box(user, boxId);
        return read.alarms(user).rows().stream().filter(row -> row.box_id().equals(boxId)).toList();
    }

    public List<CommandRow> commands(CurrentUser user, String boxId) {
        read.box(user, boxId);
        List<Map<String, Object>> operations = db.queryForList(
            "SELECT o.operation_id,o.cmd,o.status,o.created_at,o.updated_at,o.params,u.username " +
            "FROM command_operations o LEFT JOIN users u ON u.id=o.requested_by " +
            "WHERE o.box_id=? ORDER BY o.created_at DESC,o.operation_id DESC LIMIT 20", boxId);
        List<CommandRow> result = new ArrayList<>();
        for (Map<String, Object> operation : operations) {
            String id = text(operation, "operation_id");
            List<CommandNode> nodes = db.queryForList(
                "SELECT node_id,status,result,code,ack_received_at FROM node_commands WHERE operation_id=? AND box_id=? ORDER BY node_id",
                id, boxId).stream().map(row -> new CommandNode(text(row, "node_id"), text(row, "status"),
                    text(row, "result"), text(row, "code"), numberOrNull(row, "ack_received_at"))).toList();
            result.add(new CommandRow(id, text(operation, "cmd"), text(operation, "status"),
                number(operation, "created_at"), number(operation, "updated_at"), text(operation, "username"),
                text(operation, "params"), nodes));
        }
        return result;
    }

    public List<LogRow> logs(CurrentUser user, String boxId, int limit) {
        read.box(user, boxId);
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<LogRow> rows = new ArrayList<>();
        for (Map<String, Object> event : db.queryForList(
            "SELECT id,type,level,device_ts,received_at FROM events WHERE box_id=? ORDER BY received_at DESC,id DESC LIMIT 50", boxId)) {
            rows.add(new LogRow(numberOrNull(event, "device_ts") == null ? number(event, "received_at") : number(event, "device_ts"),
                "device", text(event, "type"), "设备事件 #" + number(event, "id"), text(event, "level")));
        }
        for (Map<String, Object> audit : db.queryForList(
            "SELECT action,result,occurred_at FROM audit_logs WHERE box_id=? ORDER BY occurred_at DESC,id DESC LIMIT 50", boxId)) {
            rows.add(new LogRow(number(audit, "occurred_at"), "audit", text(audit, "action"),
                "操作结果 " + text(audit, "result"), "INFO"));
        }
        for (Map<String, Object> task : db.queryForList(
            "SELECT transport_no,status,created_at,started_at,ended_at FROM transport_tasks WHERE box_id=? ORDER BY created_at DESC LIMIT 20", boxId)) {
            String transportNo = text(task, "transport_no");
            rows.add(new LogRow(number(task, "created_at"), "task", "运输创建", transportNo, "INFO"));
            if (numberOrNull(task, "started_at") != null)
                rows.add(new LogRow(number(task, "started_at"), "task", "运输开始", transportNo, "INFO"));
            if (numberOrNull(task, "ended_at") != null)
                rows.add(new LogRow(number(task, "ended_at"), "task", "运输结束", transportNo, "INFO"));
        }
        for (Map<String, Object> command : db.queryForList(
            "SELECT operation_id,cmd,status,created_at,updated_at FROM command_operations WHERE box_id=? ORDER BY created_at DESC LIMIT 20", boxId)) {
            rows.add(new LogRow(number(command, "created_at"), "command", "命令提交",
                text(command, "cmd") + " · " + text(command, "operation_id"), "INFO"));
            if (number(command, "updated_at") > number(command, "created_at"))
                rows.add(new LogRow(number(command, "updated_at"), "command", "命令状态",
                    text(command, "cmd") + " · " + text(command, "status"), "INFO"));
        }
        return rows.stream().sorted(Comparator.comparingLong(LogRow::at).reversed())
            .limit(safeLimit).toList();
    }

    private JsonNode parse(Object raw) {
        if (raw == null) return NullNode.instance;
        try { return json.readTree(raw.toString()); }
        catch (Exception invalid) { return NullNode.instance; }
    }

    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static Long numberOrNull(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).longValue();
    }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }
}
