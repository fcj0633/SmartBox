package cn.smartbox.enterprise;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import cn.smartbox.auth.AuthMapper;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.auth.UserAccount;
import cn.smartbox.common.*;
import cn.smartbox.enterprise.EnterpriseViews.*;
import cn.smartbox.transport.TransportMonitoringService;

/** Read-only projections for the enterprise console. All scope comes from the authenticated admin. */
@Service
public class EnterpriseReadService {
    private static final String TASK_SCOPE =
        "(t.enterprise_id=? OR (t.enterprise_id IS NULL AND EXISTS (SELECT 1 FROM box_ownerships o " +
        "WHERE o.box_id=t.box_id AND o.owner_type='ENTERPRISE' AND o.enterprise_id=?)))";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AuthMapper users;
    private final Clock clock;
    private final MockRouteCatalog routes;
    private final MockPositionProjector projector;
    private final TransportMonitoringService monitoring;

    public EnterpriseReadService(JdbcTemplate db, ObjectMapper json, AuthMapper users, Clock clock,
                                 MockRouteCatalog routes, MockPositionProjector projector,
                                 TransportMonitoringService monitoring) {
        this.db = db; this.json = json; this.users = users; this.clock = clock;
        this.routes = routes; this.projector = projector; this.monitoring = monitoring;
    }

    public Context context(CurrentUser user) {
        UserAccount account = user == null ? null : users.byId(user.id());
        if (account == null || !Boolean.TRUE.equals(account.enabled()) || !"LOGISTICS_OPERATOR".equals(account.roleCode()))
            throw ApiException.denied();
        var rows = db.queryForList("SELECT id,name FROM enterprises WHERE admin_user_id=?", user.id());
        if (rows.isEmpty()) throw ApiException.denied();
        return new Context(number(rows.getFirst(), "id"), text(rows.getFirst(), "name"));
    }

    public Overview overview(CurrentUser user) {
        Context context = context(user);
        long now = clock.millis();
        List<Map<String, Object>> taskRows = taskRows(context.enterprise_id());
        Map<String, List<Map<String, Object>>> byBox = taskRows.stream()
            .collect(Collectors.groupingBy(row -> text(row, "box_id")));
        List<Box> boxes = boxRows(context.enterprise_id()).stream()
            .map(row -> box(context, row, byBox.getOrDefault(text(row, "box_id"), List.of()), now))
            .toList();
        List<Task> tasks = taskRows.stream().map(row -> task(context, row, now)).toList();
        return new Overview(context.enterprise_id(), context.enterprise_name(), now, kpis(context, boxes, taskRows, now),
            boxes, tasks, activity(context, taskRows, now));
    }

    public List<Box> boxes(CurrentUser user) {
        Context context = context(user);
        long now = clock.millis();
        Map<String, List<Map<String, Object>>> byBox = taskRows(context.enterprise_id()).stream()
            .collect(Collectors.groupingBy(row -> text(row, "box_id")));
        return boxRows(context.enterprise_id()).stream()
            .map(row -> box(context, row, byBox.getOrDefault(text(row, "box_id"), List.of()), now)).toList();
    }

    public Box box(CurrentUser user, String boxId) {
        Context context = context(user);
        if (!Inputs.id(boxId)) throw ApiException.invalid();
        Map<String, Object> row = boxRow(context.enterprise_id(), boxId);
        if (row == null) throw notFound();
        List<Map<String, Object>> tasks = taskRowsForBox(context.enterprise_id(), boxId);
        return box(context, row, tasks, clock.millis());
    }

    public List<Task> tasks(CurrentUser user) {
        Context context = context(user);
        long now = clock.millis();
        return taskRows(context.enterprise_id()).stream().map(row -> task(context, row, now)).toList();
    }

    public TaskDetail taskDetail(CurrentUser user, long taskId) {
        Context context = context(user);
        long now = clock.millis();
        Map<String, Object> row = taskRow(context.enterprise_id(), taskId);
        if (row == null) throw notFound();
        Task task = task(context, row, now);
        Map<String, Object> boxRow = boxRow(context.enterprise_id(), text(row, "box_id"));
        if (boxRow == null) throw notFound();
        Box box = box(context, boxRow, taskRowsForBox(context.enterprise_id(), text(row, "box_id")), now);
        List<Sample> samples = new ArrayList<>();
        List<Event> events = new ArrayList<>();
        try {
            var view = monitoring.forEnterprise(user, taskId);
            for (var sample : view.samples()) {
                samples.add(new Sample(sample.id(), sample.observed_at(), sample.temperature(), sample.humidity(), sample.valid()));
            }
            for (var event : view.events()) {
                events.add(new Event(event.id(), event.observed_at(), event.type(), event.level(), event.description()));
            }
        } catch (ApiException ignored) {
            // Monitoring is additive; a task remains readable even when no trusted history is available.
        }
        return new TaskDetail(task, box, task.route(), task.position(), box.environment(), samples, events);
    }

    public AlarmList alarms(CurrentUser user) {
        Context context = context(user);
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT a.box_id,a.alarm_key,a.active,a.level,a.started_at,a.updated_at,a.recovered_by_event_id," +
            "b.display_name,e.type AS source_event_type,e.device_ts AS source_event_at,a.details " +
            "FROM alarms a JOIN boxes b ON b.box_id=a.box_id " +
            "JOIN box_ownerships o ON o.box_id=a.box_id AND o.owner_type='ENTERPRISE' " +
            "LEFT JOIN events e ON e.id=a.source_event_id " +
            "WHERE o.enterprise_id=? ORDER BY a.active DESC,a.updated_at DESC LIMIT 100", context.enterprise_id());
        List<AlarmRow> result = rows.stream().map(row -> new AlarmRow(
            text(row, "box_id"), text(row, "display_name"), text(row, "alarm_key"),
            bool(row, "active"), text(row, "level"), number(row, "started_at"), number(row, "updated_at"),
            bool(row, "active") ? null : number(row, "updated_at"),
            text(row, "source_event_type"), numberOrNull(row, "source_event_at"), text(row, "details"))).toList();
        long unknown = db.queryForObject(
            "SELECT COUNT(*) FROM box_ownerships o JOIN boxes b ON b.box_id=o.box_id " +
            "LEFT JOIN alarm_state s ON s.box_id=b.box_id " +
            "WHERE o.owner_type='ENTERPRISE' AND o.enterprise_id=? AND (s.box_id IS NULL OR s.assessment<>'EVALUATED')",
            Long.class, context.enterprise_id());
        return new AlarmList(unknown, result);
    }

    public List<Route> routes() { return routes.routes(); }

    private Box box(Context context, Map<String, Object> row, List<Map<String, Object>> tasks, long now) {
        String boxId = text(row, "box_id");
        List<Node> nodes = nodes(boxId, now);
        Connectivity connectivity = connectivity(nodes);
        Map<String, Object> envRow = nodeRow(boxId, "ENV");
        Map<String, Object> alarmRow = alarmState(boxId);
        Environment environment = environment(envRow, alarmRow, now);
        AlarmState alarm = new AlarmState(
            alarmRow == null ? "UNKNOWN" : text(alarmRow, "assessment"),
            alarmRow == null ? null : bool(alarmRow, "active"),
            alarmRow == null ? null : text(alarmRow, "level"),
            activeAlarmCount(boxId));
        TaskSummary current = tasks.stream().filter(t -> "ACTIVE".equals(text(t, "status"))).findFirst()
            .or(() -> tasks.stream().filter(t -> "READY".equals(text(t, "status"))).findFirst())
            .map(this::summary).orElse(null);
        TaskSummary lastCompleted = tasks.stream().filter(t -> "COMPLETED".equals(text(t, "status"))).findFirst()
            .map(this::summary).orElse(null);
        Map<String, Object> positionTask = tasks.stream().filter(t -> "ACTIVE".equals(text(t, "status"))).findFirst()
            .or(() -> tasks.stream().filter(t -> "READY".equals(text(t, "status"))).findFirst())
            .or(() -> tasks.stream().filter(t -> "COMPLETED".equals(text(t, "status"))).findFirst()).orElse(null);
        Position position = position(positionTask, connectivity.online(), connectivity.lastSeen(), now);
        Long lastSeen = nodes.stream().map(Node::last_seen_at).filter(Objects::nonNull).max(Long::compareTo).orElse(null);
        return new Box(boxId, text(row, "display_name"), text(row, "asset_source"), text(row, "purpose"),
            text(row, "notes"), text(row, "demo_scenario"), bool(row, "demo_enabled"), connectivity.connectivity(),
            environment.data_state(), environment, alarm, current, lastCompleted, position, lastSeen, nodes);
    }

    private Task task(Context context, Map<String, Object> row, long now) {
        String boxId = text(row, "box_id");
        Connectivity connectivity = connectivity(nodes(boxId, now));
        Map<String, Object> alarmRow = alarmState(boxId);
        boolean risk = alarmRow == null || !"EVALUATED".equals(text(alarmRow, "assessment")) || Boolean.TRUE.equals(bool(alarmRow, "active"));
        List<String> blockers = new ArrayList<>();
        if (!connectivity.online()) blockers.add("必需设备节点不在线");
        if (risk) blockers.add("存在活动告警或告警判定未知，需要显式确认");
        Route route = parseRoute(row);
        Position position = position(row, connectivity.online(), connectivity.lastSeen(), now);
        return new Task(number(row, "id"), text(row, "transport_no"), boxId, text(row, "box_display_name"),
            text(row, "status"), text(row, "item_name"), text(row, "item_description"),
            text(row, "operator_username"), number(row, "created_at"), numberOrNull(row, "started_at"),
            numberOrNull(row, "ended_at"), route, position, new StartCheck(connectivity.online(), risk, blockers));
    }

    private TaskSummary summary(Map<String, Object> row) {
        return new TaskSummary(number(row, "id"), text(row, "transport_no"), text(row, "status"),
            text(row, "item_name"), text(row, "box_id"), text(row, "box_display_name"),
            numberOrNull(row, "started_at"), numberOrNull(row, "ended_at"));
    }

    private Position position(Map<String, Object> task, boolean online, Long lastSeen, long now) {
        if (task == null) return null;
        Route route = parseRoute(task);
        return projector.project(route, text(task, "status"), numberOrNull(task, "started_at"),
            numberOrNull(task, "ended_at"), now, online, lastSeen);
    }

    private Route parseRoute(Map<String, Object> row) {
        Object raw = row.get("route_snapshot");
        if (raw == null) return null;
        try {
            JsonNode node = json.readTree(raw.toString());
            if (!node.isObject() || !node.path("points").isArray()) return null;
            return json.convertValue(node, Route.class);
        } catch (Exception invalid) {
            return null;
        }
    }

    private Kpis kpis(Context context, List<Box> boxes, List<Map<String, Object>> tasks, long now) {
        long online = boxes.stream().filter(b -> "ONLINE".equals(b.connectivity())).count();
        long partial = boxes.stream().filter(b -> "PARTIAL".equals(b.connectivity())).count();
        long offline = boxes.stream().filter(b -> "OFFLINE".equals(b.connectivity())).count();
        long unknown = boxes.stream().filter(b -> "UNKNOWN".equals(b.connectivity())).count();
        long active = tasks.stream().filter(t -> "ACTIVE".equals(text(t, "status"))).count();
        long activeAlarmBoxes = boxes.stream().filter(b -> b.alarm().active() != null && b.alarm().active()).count();
        long unknownAlarmBoxes = boxes.stream().filter(b -> !"EVALUATED".equals(b.alarm().assessment())).count();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long startOfDay = LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant().toEpochMilli();
        Long completed = db.queryForObject("SELECT COUNT(*) FROM transport_tasks t WHERE " + TASK_SCOPE +
            " AND t.status='COMPLETED' AND t.ended_at>=?", Long.class, context.enterprise_id(), context.enterprise_id(), startOfDay);
        return new Kpis(boxes.size(), online, partial, offline, unknown, active, activeAlarmBoxes, unknownAlarmBoxes,
            completed == null ? 0 : completed);
    }

    private List<Activity> activity(Context context, List<Map<String, Object>> tasks, long now) {
        List<Activity> items = new ArrayList<>();
        for (Map<String, Object> task : tasks) {
            long id = number(task, "id");
            String box = text(task, "box_id");
            String name = text(task, "box_display_name");
            items.add(new Activity(number(task, "created_at"), "TASK_CREATED", box, name, id,
                "创建运输 " + text(task, "transport_no"), "task"));
            if (numberOrNull(task, "started_at") != null)
                items.add(new Activity(number(task, "started_at"), "TASK_STARTED", box, name, id,
                    "开始运输 " + text(task, "transport_no"), "task"));
            if (numberOrNull(task, "ended_at") != null)
                items.add(new Activity(number(task, "ended_at"), "TASK_COMPLETED", box, name, id,
                    "结束运输 " + text(task, "transport_no"), "task"));
        }
        for (Map<String, Object> alarm : db.queryForList(
            "SELECT a.box_id,b.display_name,a.alarm_key,a.active,a.level,a.started_at,a.updated_at " +
            "FROM alarms a JOIN boxes b ON b.box_id=a.box_id " +
            "JOIN box_ownerships o ON o.box_id=a.box_id AND o.owner_type='ENTERPRISE' " +
            "WHERE o.enterprise_id=? ORDER BY a.updated_at DESC LIMIT 12", context.enterprise_id())) {
            items.add(new Activity(number(alarm, "started_at"), "ALARM_STARTED", text(alarm, "box_id"),
                text(alarm, "display_name"), null,
                "告警触发 · " + text(alarm, "level") + " · " + text(alarm, "alarm_key"), "alarm"));
            if (!bool(alarm, "active"))
                items.add(new Activity(number(alarm, "updated_at"), "ALARM_RECOVERED", text(alarm, "box_id"),
                    text(alarm, "display_name"), null, "告警恢复 · " + text(alarm, "alarm_key"), "alarm"));
        }
        for (Map<String, Object> event : db.queryForList(
            "SELECT e.id,e.box_id,b.display_name,e.type,e.level,e.device_ts,e.received_at " +
            "FROM events e JOIN boxes b ON b.box_id=e.box_id " +
            "JOIN box_ownerships o ON o.box_id=e.box_id AND o.owner_type='ENTERPRISE' " +
            "WHERE o.enterprise_id=? ORDER BY e.received_at DESC LIMIT 10", context.enterprise_id())) {
            long at = numberOrNull(event, "device_ts") == null ? number(event, "received_at") : number(event, "device_ts");
            items.add(new Activity(at, "DEVICE_EVENT", text(event, "box_id"), text(event, "display_name"), null,
                "设备事件 · " + text(event, "type"), "device"));
        }
        return items.stream().sorted(Comparator.comparingLong(Activity::at).reversed()).limit(8).toList();
    }

    private List<Map<String, Object>> boxRows(long enterpriseId) {
        return db.queryForList(
            "SELECT b.box_id,b.display_name,b.asset_source,b.purpose,b.notes,b.demo_scenario,b.demo_enabled " +
            "FROM box_ownerships o JOIN boxes b ON b.box_id=o.box_id " +
            "WHERE o.owner_type='ENTERPRISE' AND o.enterprise_id=? ORDER BY b.box_id", enterpriseId);
    }

    private Map<String, Object> boxRow(long enterpriseId, String boxId) {
        var rows = db.queryForList(
            "SELECT b.box_id,b.display_name,b.asset_source,b.purpose,b.notes,b.demo_scenario,b.demo_enabled " +
            "FROM box_ownerships o JOIN boxes b ON b.box_id=o.box_id " +
            "WHERE o.owner_type='ENTERPRISE' AND o.enterprise_id=? AND b.box_id=?", enterpriseId, boxId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Event> boxEvents(CurrentUser user, String boxId) {
        Context context = context(user);
        if (!Inputs.id(boxId) || boxRow(context.enterprise_id(), boxId) == null) throw notFound();
        return db.queryForList(
                "SELECT e.id,e.type,e.level,e.device_ts,e.received_at FROM events e " +
                "WHERE e.box_id=? ORDER BY e.received_at DESC,e.id DESC LIMIT 10", boxId)
            .stream().map(row -> new Event(number(row, "id"),
                numberOrNull(row, "device_ts") == null ? number(row, "received_at") : number(row, "device_ts"),
                text(row, "type"), text(row, "level"), eventDescription(text(row, "type")))).toList();
    }

    private static String eventDescription(String type) {
        return switch (type == null ? "" : type) {
            case "ENV_HIGH" -> "环境值超过上限";
            case "ENV_LOW" -> "环境值低于下限";
            case "ENV_RECOVERED" -> "环境值恢复";
            case "IMPACT" -> "设备检测到冲击";
            case "POSE_CHANGED" -> "设备姿态变化";
            case "DOOR_EVIDENCE" -> "设备开合证据";
            case "SENTRY_INTRUSION" -> "哨兵异常提示";
            case "GEOFENCE_EXIT" -> "离开模拟围栏";
            case "GEOFENCE_ENTER" -> "返回模拟围栏";
            case "DEVICE_FAULT" -> "设备故障";
            default -> "设备状态记录";
        };
    }

    private List<Map<String, Object>> taskRows(long enterpriseId) {
        return db.queryForList(
            "SELECT t.*,b.display_name AS box_display_name,COALESCE(u.username,r.username) AS operator_username " +
            "FROM transport_tasks t JOIN boxes b ON b.box_id=t.box_id " +
            "LEFT JOIN users u ON u.id=t.operator_user_id LEFT JOIN users r ON r.id=t.rider_id WHERE " +
            TASK_SCOPE + " ORDER BY t.created_at DESC,t.id DESC LIMIT 50", enterpriseId, enterpriseId);
    }

    private List<Map<String, Object>> taskRowsForBox(long enterpriseId, String boxId) {
        return db.queryForList(
            "SELECT t.*,b.display_name AS box_display_name,COALESCE(u.username,r.username) AS operator_username " +
            "FROM transport_tasks t JOIN boxes b ON b.box_id=t.box_id " +
            "LEFT JOIN users u ON u.id=t.operator_user_id LEFT JOIN users r ON r.id=t.rider_id " +
            "WHERE t.box_id=? AND " + TASK_SCOPE + " ORDER BY t.created_at DESC,t.id DESC LIMIT 50",
            boxId, enterpriseId, enterpriseId);
    }

    Map<String, Object> taskRow(long enterpriseId, long taskId) {
        var rows = db.queryForList(
            "SELECT t.*,b.display_name AS box_display_name,COALESCE(u.username,r.username) AS operator_username " +
            "FROM transport_tasks t JOIN boxes b ON b.box_id=t.box_id " +
            "LEFT JOIN users u ON u.id=t.operator_user_id LEFT JOIN users r ON r.id=t.rider_id " +
            "WHERE t.id=? AND " + TASK_SCOPE, taskId, enterpriseId, enterpriseId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Map<String, Object>> nodeRows(String boxId) {
        return db.queryForList(
            "SELECT n.node_id,n.role,n.caps,n.required,n.last_received_at,r.online,r.last_seen,l.payload,l.received_at AS sample_received_at " +
            "FROM nodes n LEFT JOIN node_runtime r ON r.node_id=n.node_id " +
            "LEFT JOIN node_latest l ON l.node_id=n.node_id WHERE n.box_id=? ORDER BY n.role", boxId);
    }

    private Map<String, Object> nodeRow(String boxId, String role) {
        var rows = db.queryForList(
            "SELECT n.node_id,n.role,n.caps,n.required,n.last_received_at,r.online,r.last_seen,l.payload,l.received_at AS sample_received_at " +
            "FROM nodes n LEFT JOIN node_runtime r ON r.node_id=n.node_id " +
            "LEFT JOIN node_latest l ON l.node_id=n.node_id WHERE n.box_id=? AND n.role=?", boxId, role);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<Node> nodes(String boxId, long now) {
        return nodeRows(boxId).stream().map(row -> new Node(text(row, "node_id"), text(row, "role"),
            ((Number) row.get("caps")).intValue(), bool(row, "required"), online(row, now),
            numberOrNull(row, "last_seen"), numberOrNull(row, "last_received_at"))).toList();
    }

    private Connectivity connectivity(List<Node> nodes) {
        int required = (int) nodes.stream().filter(Node::required).count();
        int online = (int) nodes.stream().filter(n -> n.required() && n.online()).count();
        Long lastSeen = nodes.stream().map(Node::last_seen_at).filter(Objects::nonNull).max(Long::compareTo).orElse(null);
        if (required == 0) return new Connectivity("UNKNOWN", false, 0, 0, lastSeen);
        String state = online == required ? "ONLINE" : online == 0 ? "OFFLINE" : "PARTIAL";
        return new Connectivity(state, online == required, required, online, lastSeen);
    }

    private record Connectivity(String connectivity, boolean online, int required, int onlineCount, Long lastSeen) {}

    private boolean online(Map<String, Object> runtimeRow, long now) {
        if (runtimeRow == null) return false;
        Object online = runtimeRow.get("online");
        boolean flag = Boolean.TRUE.equals(online) || (online instanceof Number number && number.intValue() != 0);
        Long last = numberOrNull(runtimeRow, "last_seen");
        return flag && last != null && now - last < 90_000;
    }

    private Environment environment(Map<String, Object> envRow, Map<String, Object> alarmRow, long now) {
        String assessment = alarmRow == null ? "UNKNOWN" : text(alarmRow, "assessment");
        String level = alarmRow == null ? null : text(alarmRow, "level");
        if (envRow == null) return new Environment(assessment, level, null, null, null, null, "UNKNOWN", "UNKNOWN", null);
        JsonNode payload = parse(envRow.get("payload"));
        JsonNode env = payload.path("env");
        Double temperature = env.path("temp_c").isNumber() ? env.path("temp_c").asDouble() : null;
        Double humidity = env.path("humi_pct").isNumber() ? env.path("humi_pct").asDouble() : null;
        Double lux = env.path("lux").isNumber() ? env.path("lux").asDouble() : null;
        Long observed = payload.path("ts").isNumber() ? payload.path("ts").asLong() : null;
        String quality = payload.path("ts_src").asText("UNKNOWN");
        Long received = numberOrNull(envRow, "sample_received_at");
        String dataState;
        if (!env.isObject()) dataState = "UNKNOWN";
        else if (!env.path("valid").asBoolean(false)) dataState = "INVALID";
        else if (received == null) dataState = "UNKNOWN";
        else if (now - received > 5_000) dataState = "STALE";
        else dataState = "FRESH";
        return new Environment(assessment, level, temperature, humidity, lux, observed, quality, dataState, received);
    }

    private Map<String, Object> alarmState(String boxId) {
        var rows = db.queryForList("SELECT assessment,active,level FROM alarm_state WHERE box_id=?", boxId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private long activeAlarmCount(String boxId) {
        Long count = db.queryForObject("SELECT COUNT(*) FROM alarms WHERE box_id=? AND active=1", Long.class, boxId);
        return count == null ? 0 : count;
    }

    private JsonNode parse(Object raw) {
        if (raw == null) return NullNode.instance;
        try {
            return json.readTree(raw.toString());
        } catch (Exception invalid) {
            return NullNode.instance;
        }
    }

    private static ApiException notFound() { return new ApiException(404, "NOT_FOUND", "资源不存在或无权访问"); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static Long numberOrNull(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).longValue();
    }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }
    private static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0);
    }
}
