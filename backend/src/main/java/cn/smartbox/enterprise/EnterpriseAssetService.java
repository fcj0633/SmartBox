package cn.smartbox.enterprise;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import cn.smartbox.audit.AuditService;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.common.*;
import cn.smartbox.enterprise.EnterpriseViews.Box;
import cn.smartbox.enterprise.EnterpriseViews.Context;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Enterprise simulation asset metadata and scenario writes. REAL identity is never modified. */
@Service
public class EnterpriseAssetService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> SCENARIOS = Set.of("normal", "env_high", "offline", "stale", "impact");
    private final JdbcTemplate db;
    private final EnterpriseReadService read;
    private final DemoScenarioService facts;
    private final ObjectProvider<DemoFleetService> demoFleet;
    private final AuditService audit;
    private final Clock clock;

    public EnterpriseAssetService(JdbcTemplate db, EnterpriseReadService read, DemoScenarioService facts,
                                  ObjectProvider<DemoFleetService> demoFleet, AuditService audit, Clock clock) {
        this.db = db; this.read = read; this.facts = facts; this.demoFleet = demoFleet;
        this.audit = audit; this.clock = clock;
    }

    @Transactional
    public Box create(CurrentUser user, String displayName, String purpose, String notes, String scenario) {
        Context context = read.context(user);
        String name = displayName(displayName);
        String use = purpose(purpose);
        String remark = notes(notes);
        String selected = scenario == null || scenario.isBlank() ? "normal" : scenario(scenario);
        String boxId = uniqueBoxId();
        long now = clock.millis();
        try {
            db.update("INSERT INTO boxes(box_id,display_name,asset_source,purpose,notes,demo_scenario,demo_enabled,created_at,updated_at) " +
                    "VALUES(?,?, 'SIMULATED',?,?,?,1,?,?)", boxId, name, use, remark, selected, now, now);
            for (String role : List.of("ENV", "POSE")) {
                db.update("INSERT INTO nodes(node_id,box_id,role,caps,required,created_at,updated_at) VALUES(?,?,?,?,1,?,?)",
                    boxId + "-" + role, boxId, role, role.equals("ENV") ? 71 : 120, now, now);
            }
            db.update("INSERT INTO box_ownerships(box_id,owner_type,enterprise_id,owner_user_id,created_at) VALUES(?,'ENTERPRISE',?,NULL,?)",
                boxId, context.enterprise_id(), now);
        } catch (DuplicateKeyException conflict) {
            throw new ApiException(409, "ASSET_ID_CONFLICT", "箱体编号冲突，请重试");
        }
        afterCommit(user.id(), boxId, "ASSET_CREATE");
        return read.box(user, boxId);
    }

    @Transactional
    public Box edit(CurrentUser user, String boxId, String displayName, String purpose, String notes) {
        Context context = read.context(user);
        if (!Inputs.id(boxId)) throw ApiException.invalid();
        lockOwnedBox(context, boxId);
        String name = displayName(displayName);
        String use = purpose(purpose);
        String remark = notes(notes);
        db.update("UPDATE boxes SET display_name=?,purpose=?,notes=?,updated_at=? WHERE box_id=?",
            name, use, remark, clock.millis(), boxId);
        afterCommit(user.id(), boxId, "ASSET_EDIT");
        return read.box(user, boxId);
    }

    @Transactional
    public Box scenario(CurrentUser user, String boxId, String scenario) {
        Context context = read.context(user);
        if (!Inputs.id(boxId)) throw ApiException.invalid();
        lockOwnedBox(context, boxId);
        Map<String, Object> box = boxRow(boxId);
        if (!"SIMULATED".equals(text(box, "asset_source")))
            throw new ApiException(409, "ASSET_NOT_SIMULATED", "仅模拟箱体可切换演示场景");
        String selected = scenario(scenario);
        // Generate controlled events first; a failure leaves the persisted scenario unchanged.
        facts.apply(boxId, selected);
        db.update("UPDATE boxes SET demo_scenario=?,updated_at=? WHERE box_id=?", selected, clock.millis(), boxId);
        afterCommit(user.id(), boxId, "ASSET_SCENARIO");
        DemoFleetService fleet = demoFleet.getIfAvailable();
        if (fleet != null) fleet.refresh(boxId);
        return read.box(user, boxId);
    }

    @Transactional
    public Box disable(CurrentUser user, String boxId) {
        Context context = read.context(user);
        if (!Inputs.id(boxId)) throw ApiException.invalid();
        lockOwnedBox(context, boxId);
        Map<String, Object> box = boxRow(boxId);
        if (!"SIMULATED".equals(text(box, "asset_source")))
            throw new ApiException(409, "ASSET_NOT_SIMULATED", "仅模拟箱体可停用模拟输入");
        Long active = db.queryForObject("SELECT COUNT(*) FROM transport_tasks WHERE box_id=? AND status='ACTIVE'", Long.class, boxId);
        if (active != null && active > 0) throw new ApiException(409, "BOX_IN_USE", "箱体正在运输中，不能停用模拟输入");
        db.update("UPDATE boxes SET demo_enabled=0,updated_at=? WHERE box_id=?", clock.millis(), boxId);
        DemoFleetService fleet = demoFleet.getIfAvailable();
        if (fleet != null) fleet.markOffline(boxId);
        afterCommit(user.id(), boxId, "ASSET_DISABLE");
        return read.box(user, boxId);
    }

    @Transactional
    public Box enable(CurrentUser user, String boxId) {
        Context context = read.context(user);
        if (!Inputs.id(boxId)) throw ApiException.invalid();
        lockOwnedBox(context, boxId);
        Map<String, Object> box = boxRow(boxId);
        if (!"SIMULATED".equals(text(box, "asset_source")))
            throw new ApiException(409, "ASSET_NOT_SIMULATED", "仅模拟箱体可启用模拟输入");
        db.update("UPDATE boxes SET demo_enabled=1,updated_at=? WHERE box_id=?", clock.millis(), boxId);
        afterCommit(user.id(), boxId, "ASSET_ENABLE");
        DemoFleetService fleet = demoFleet.getIfAvailable();
        if (fleet != null) fleet.refresh(boxId);
        return read.box(user, boxId);
    }

    private Map<String, Object> lockOwnedBox(Context context, String boxId) {
        var rows = db.queryForList("SELECT box_id,owner_type,enterprise_id FROM box_ownerships WHERE box_id=? FOR UPDATE", boxId);
        if (rows.isEmpty()) throw notFound();
        Map<String, Object> row = rows.getFirst();
        if (!"ENTERPRISE".equals(text(row, "owner_type")) || !Objects.equals(number(row, "enterprise_id"), context.enterprise_id()))
            throw notFound();
        return row;
    }

    private Map<String, Object> boxRow(String boxId) {
        var rows = db.queryForList("SELECT box_id,asset_source,demo_scenario,demo_enabled FROM boxes WHERE box_id=?", boxId);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private String uniqueBoxId() {
        for (int i = 0; i < 8; i++) {
            byte[] bytes = new byte[4];
            RANDOM.nextBytes(bytes);
            String candidate = "BOX-SIM-" + HexFormat.of().withUpperCase().formatHex(bytes);
            if (db.queryForList("SELECT 1 FROM boxes WHERE box_id=?", candidate).isEmpty()) return candidate;
        }
        throw new IllegalStateException("Cannot generate simulated box id");
    }

    private String displayName(String raw) {
        if (raw == null) throw new ApiException(400, "VALIDATION_ERROR", "请填写箱体展示名");
        String value = raw.strip();
        if (!Inputs.length(value, 1, 128)) throw new ApiException(400, "VALIDATION_ERROR", "箱体展示名长度不合法");
        return value;
    }

    private static String purpose(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (value.isEmpty()) return null;
        if (!Inputs.length(value, 1, 120)) throw new ApiException(400, "VALIDATION_ERROR", "用途长度不合法");
        return value;
    }

    private static String notes(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (value.isEmpty()) return null;
        if (!Inputs.length(value, 1, 500)) throw new ApiException(400, "VALIDATION_ERROR", "备注长度不合法");
        return value;
    }

    private static String scenario(String raw) {
        if (raw == null || raw.isBlank() || !SCENARIOS.contains(raw)) 
            throw new ApiException(400, "INVALID_SCENARIO", "模拟场景不存在");
        return raw;
    }

    private void afterCommit(long userId, String boxId, String action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { audit.record(userId, null, boxId, action, "SUCCEEDED"); }
        });
    }

    private static ApiException notFound() { return new ApiException(404, "NOT_FOUND", "箱体不存在或无权访问"); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }
}
