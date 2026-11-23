package cn.smartbox.enterprise;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import cn.smartbox.audit.AuditService;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.common.*;
import cn.smartbox.enterprise.EnterpriseViews.Context;
import cn.smartbox.enterprise.EnterpriseViews.Route;
import cn.smartbox.enterprise.EnterpriseViews.TaskDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Enterprise-owned transport writes. Rider endpoints and their contracts remain untouched. */
@Service
public class EnterpriseTransportService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate db;
    private final EnterpriseReadService read;
    private final MockRouteCatalog routes;
    private final ObjectMapper json;
    private final AuditService audit;
    private final Clock clock;

    public EnterpriseTransportService(JdbcTemplate db, EnterpriseReadService read, MockRouteCatalog routes,
                                      ObjectMapper json, AuditService audit, Clock clock) {
        this.db = db; this.read = read; this.routes = routes; this.json = json; this.audit = audit; this.clock = clock;
    }

    @Transactional
    public TaskDetail create(CurrentUser user, String boxId, String itemName, String itemDescription, String routeCode) {
        Context context = read.context(user);
        if (!Inputs.id(boxId)) throw ApiException.invalid();
        String item = item(itemName);
        String description = description(itemDescription);
        Route route = route(routeCode);
        lockOwnedBox(context, boxId);
        long now = clock.millis();
        String transportNo = transportNo();
        try {
            db.update("INSERT INTO transport_tasks(transport_no,box_id,rider_id,operator_user_id,enterprise_id,status," +
                    "item_name,item_description,created_at,updated_at,route_code,route_snapshot) " +
                    "VALUES(?,?,NULL,?,?,'READY',?,?,?,?,?,?)",
                transportNo, boxId, user.id(), context.enterprise_id(), item, description, now, now,
                route.code(), routeJson(route));
        } catch (DuplicateKeyException conflict) {
            throw new ApiException(409, "TRANSPORT_NO_CONFLICT", "运输编号冲突，请重试");
        }
        Long id = db.queryForObject("SELECT id FROM transport_tasks WHERE transport_no=?", Long.class, transportNo);
        if (id == null) throw new ApiException(409, "CONFLICT", "运输任务创建失败");
        afterCommit(user.id(), boxId, "TRANSPORT_CREATE");
        return read.taskDetail(user, id);
    }

    @Transactional
    public TaskDetail edit(CurrentUser user, long id, String itemName, String itemDescription, String routeCode) {
        Context context = read.context(user);
        Map<String, Object> current = read.taskRow(context.enterprise_id(), id);
        if (current == null) throw notFound();
        String boxId = text(current, "box_id");
        lockOwnedBox(context, boxId);
        Map<String, Object> locked = lockTask(context, id, boxId);
        if (!"READY".equals(text(locked, "status")))
            throw new ApiException(409, "INVALID_TASK_STATE", "仅待开始任务可编辑");
        String item = item(itemName);
        String description = description(itemDescription);
        Route route = route(routeCode);
        int updated = db.update("UPDATE transport_tasks SET item_name=?,item_description=?,route_code=?,route_snapshot=?,updated_at=? " +
                "WHERE id=? AND status='READY'",
            item, description, route.code(), routeJson(route), clock.millis(), id);
        if (updated == 0) throw new ApiException(409, "INVALID_TASK_STATE", "当前任务状态不允许该操作");
        afterCommit(user.id(), boxId, "TRANSPORT_EDIT");
        return read.taskDetail(user, id);
    }

    @Transactional
    public TaskDetail start(CurrentUser user, long id, boolean confirmRisk) {
        Context context = read.context(user);
        Map<String, Object> current = read.taskRow(context.enterprise_id(), id);
        if (current == null) throw notFound();
        String boxId = text(current, "box_id");
        lockOwnedBox(context, boxId);
        Map<String, Object> locked = lockTask(context, id, boxId);
        String status = text(locked, "status");
        if ("ACTIVE".equals(status)) return read.taskDetail(user, id);
        if (!"READY".equals(status)) throw new ApiException(409, "INVALID_TASK_STATE", "当前任务状态不允许开始");
        if (text(locked, "item_name") == null || text(locked, "item_name").isBlank())
            throw new ApiException(400, "VALIDATION_ERROR", "请先填写运输物品");
        long now = clock.millis();
        if (!online(boxId, now)) throw new ApiException(409, "BOX_OFFLINE", "箱体必需节点不在线");
        boolean risk = riskRequired(boxId);
        if (risk && !confirmRisk)
            throw new ApiException(409, "RISK_CONFIRMATION_REQUIRED", "存在未知或活动告警，请确认后重试");
        try {
            int updated = db.update("UPDATE transport_tasks SET status='ACTIVE',started_at=?,updated_at=? " +
                "WHERE id=? AND status='READY'", now, now, id);
            if (updated == 0) {
                Map<String, Object> latest = read.taskRow(context.enterprise_id(), id);
                if (latest != null && "ACTIVE".equals(text(latest, "status"))) return read.taskDetail(user, id);
                throw new ApiException(409, "INVALID_TASK_STATE", "当前任务状态不允许开始");
            }
        } catch (DuplicateKeyException conflict) {
            throw new ApiException(409, "BOX_IN_USE", "该箱体已在运输中");
        }
        if (risk) afterCommit(user.id(), boxId, "TRANSPORT_RISK_CONFIRM");
        afterCommit(user.id(), boxId, "TRANSPORT_START");
        return read.taskDetail(user, id);
    }

    @Transactional
    public TaskDetail finish(CurrentUser user, long id) {
        Context context = read.context(user);
        Map<String, Object> current = read.taskRow(context.enterprise_id(), id);
        if (current == null) throw notFound();
        String boxId = text(current, "box_id");
        lockOwnedBox(context, boxId);
        Map<String, Object> locked = lockTask(context, id, boxId);
        String status = text(locked, "status");
        if ("COMPLETED".equals(status)) return read.taskDetail(user, id);
        if (!"ACTIVE".equals(status)) throw new ApiException(409, "INVALID_TASK_STATE", "仅进行中任务可结束");
        int updated = db.update("UPDATE transport_tasks SET status='COMPLETED',ended_at=?,updated_at=? " +
            "WHERE id=? AND status='ACTIVE'", clock.millis(), clock.millis(), id);
        if (updated == 0) {
            Map<String, Object> latest = read.taskRow(context.enterprise_id(), id);
            if (latest != null && "COMPLETED".equals(text(latest, "status"))) return read.taskDetail(user, id);
            throw new ApiException(409, "INVALID_TASK_STATE", "当前任务状态不允许结束");
        }
        afterCommit(user.id(), boxId, "TRANSPORT_FINISH");
        return read.taskDetail(user, id);
    }

    private Map<String, Object> lockOwnedBox(Context context, String boxId) {
        var rows = db.queryForList("SELECT box_id,owner_type,enterprise_id,owner_user_id FROM box_ownerships WHERE box_id=? FOR UPDATE", boxId);
        if (rows.isEmpty()) throw ApiException.denied();
        Map<String, Object> row = rows.getFirst();
        if (!"ENTERPRISE".equals(text(row, "owner_type")) || !Objects.equals(number(row, "enterprise_id"), context.enterprise_id()))
            throw ApiException.denied();
        return row;
    }

    private Map<String, Object> lockTask(Context context, long id, String boxId) {
        var rows = db.queryForList("SELECT * FROM transport_tasks WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw notFound();
        Map<String, Object> row = rows.getFirst();
        if (!Objects.equals(text(row, "box_id"), boxId)) throw notFound();
        Long enterprise = numberOrNull(row, "enterprise_id");
        if (enterprise != null && !Objects.equals(enterprise, context.enterprise_id())) throw notFound();
        return row;
    }

    private boolean online(String boxId, long now) {
        Map<String, Object> row = db.queryForMap(
            "SELECT COUNT(*) AS required_count,COALESCE(SUM(CASE WHEN r.online=1 AND r.last_seen>=? THEN 1 ELSE 0 END),0) AS online_count " +
            "FROM nodes n LEFT JOIN node_runtime r ON r.node_id=n.node_id WHERE n.box_id=? AND n.required=1",
            now - 90_000, boxId);
        long required = number(row, "required_count"), online = number(row, "online_count");
        return required > 0 && online == required;
    }

    private boolean riskRequired(String boxId) {
        var rows = db.queryForList("SELECT assessment,active FROM alarm_state WHERE box_id=?", boxId);
        if (rows.isEmpty()) return true;
        Map<String, Object> row = rows.getFirst();
        if (!"EVALUATED".equals(text(row, "assessment"))) return true;
        Object active = row.get("active");
        if (active instanceof Boolean value) return value;
        return active instanceof Number value && value.intValue() != 0;
    }

    private Route route(String raw) {
        if (raw == null || raw.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "请选择模拟路线");
        Route route = routes.byCode(raw.strip());
        if (route == null) throw new ApiException(400, "VALIDATION_ERROR", "模拟路线不存在");
        return route;
    }

    private static String item(String raw) {
        if (raw == null) throw new ApiException(400, "VALIDATION_ERROR", "请填写运输物品");
        String value = raw.strip();
        if (!Inputs.length(value, 1, 50)) throw new ApiException(400, "VALIDATION_ERROR", "运输物品长度不合法");
        return value;
    }

    private static String description(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (value.isEmpty()) return null;
        if (!Inputs.length(value, 1, 200)) throw new ApiException(400, "VALIDATION_ERROR", "物品说明过长");
        return value;
    }

    private String routeJson(Route route) {
        try {
            return json.writeValueAsString(route);
        } catch (Exception invalid) {
            throw new IllegalStateException("Route snapshot serialization failed");
        }
    }

    private static String transportNo() {
        byte[] bytes = new byte[10];
        RANDOM.nextBytes(bytes);
        return "TR-" + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private void afterCommit(long userId, String boxId, String action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { audit.record(userId, null, boxId, action, "SUCCEEDED"); }
        });
    }

    private static ApiException notFound() { return new ApiException(404, "NOT_FOUND", "任务不存在或无权访问"); }
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
