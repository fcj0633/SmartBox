package cn.smartbox;

import cn.smartbox.asset.AssetBootstrapService;
import cn.smartbox.asset.DemoAssetConfig;
import cn.smartbox.auth.AuthMapper;
import cn.smartbox.config.BootstrapService;
import cn.smartbox.enterprise.DemoFleetService;
import cn.smartbox.live.DomainEventWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"smartbox.workers.enabled=false", "smartbox.demo.fleet=true", "smartbox.demo.fleet.tick=false"})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class EnterpriseConsoleIT {
    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired BootstrapService bootstrap;
    @Autowired AssetBootstrapService seed; @Autowired DemoAssetConfig config; @Autowired AuthMapper users;
    @Autowired DemoFleetService demoFleet; @Autowired DomainEventWorker worker;
    @LocalServerPort int port;
    JsonNode fixture; final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll void setup() throws Exception {
        TestDatabaseGuard.require(System.getenv("SPRING_PROFILES_ACTIVE"), System.getenv("SMARTBOX_DB_URL"),
            db.queryForObject("SELECT DATABASE()", String.class), db.queryForObject("SELECT CURRENT_USER()", String.class),
            System.getenv("SMARTBOX_TEST_ALLOW_RESET"));
        for (String table : List.of("transport_guest_access", "transport_tasks", "box_assignments", "box_ownerships",
            "enterprises", "command_receipts", "command_dispatch", "idempotency_records", "node_commands",
            "command_operations", "box_domain_views", "alarm_state", "alarms", "box_domain_state", "door_conclusions",
            "event_evidence", "event_views", "processing_outbox", "events", "location_samples", "node_latest",
            "telemetry", "node_runtime", "audit_logs", "user_box_access", "users", "nodes", "boxes"))
            db.update("DELETE FROM " + table);
        fixture = json.readTree(Files.readString(Path.of(System.getenv("SMARTBOX_BOOTSTRAP_FILE"))));
        bootstrap.initialize(fixture);
        seed.initialize(config.seed());
    }

    @BeforeEach void reset() {
        db.update("DELETE FROM transport_tasks");
        db.update("DELETE FROM alarms");
        db.update("DELETE FROM alarm_state");
        db.update("UPDATE nodes SET last_received_at=? WHERE box_id='BOX-0001'", System.currentTimeMillis());
        long now = System.currentTimeMillis();
        for (String node : db.queryForList("SELECT node_id FROM nodes WHERE box_id='BOX-0001' AND required=1", String.class)) {
            db.update("INSERT INTO node_runtime(node_id,boot_id,online,last_seen,uptime_ms,ts_src) VALUES(?,1,1,?,1000,'SNTP') " +
                "ON DUPLICATE KEY UPDATE online=1,last_seen=VALUES(last_seen)", node, now);
        }
        db.update("INSERT INTO alarm_state(box_id,assessment,active,level,updated_at) VALUES('BOX-0001','EVALUATED',0,NULL,?) " +
            "ON DUPLICATE KEY UPDATE assessment='EVALUATED',active=0,level=NULL,updated_at=VALUES(updated_at)", now);
    }

    record Reply(int status, JsonNode body) {}

    Reply request(String method, String path, String token, JsonNode body) throws Exception {
        return request(method, path, token, body, Map.of());
    }

    Reply request(String method, String path, String token, JsonNode body, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        headers.forEach(builder::header);
        if (body != null) builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), json.readTree(response.body()));
    }

    JsonNode account(String role) {
        for (JsonNode user : fixture.path("users"))
            if (role.equals(user.path("role").asText()) && user.path("box_ids").size() > 0 && "BOX-0001".equals(user.path("box_ids").get(0).asText()))
                return user;
        throw new AssertionError("missing test account " + role);
    }

    String login(String role) throws Exception {
        var account = account(role);
        ObjectNode body = json.createObjectNode().put("username", account.path("username").asText()).put("password", account.path("password").asText());
        var reply = request("POST", "/auth/login", null, body);
        assertEquals(200, reply.status());
        return reply.body().path("data").path("access_token").asText();
    }

    Reply create(String token, String item, String route) throws Exception {
        return request("POST", "/enterprise/console/transports", token,
            json.createObjectNode().put("box_id", "BOX-0001").put("item_name", item).put("item_description", "演示").put("route_code", route));
    }

    Reply start(String token, long id, boolean confirm) throws Exception {
        return request("POST", "/enterprise/console/transports/" + id + "/start", token, json.createObjectNode().put("confirm_risk", confirm));
    }

    Reply finish(String token, long id) throws Exception {
        return request("POST", "/enterprise/console/transports/" + id + "/finish", token, null);
    }

    @Test void overviewAndBoxAreScopedToEnterprise() throws Exception {
        String operator = login("LOGISTICS_OPERATOR");
        var overview = request("GET", "/enterprise/console/overview", operator, null);
        assertEquals(200, overview.status());
        assertTrue(overview.body().path("data").path("boxes").isArray());
        assertTrue(overview.body().path("data").path("boxes").toString().contains("BOX-0001"));
        assertEquals(200, request("GET", "/enterprise/console/boxes/BOX-0001", operator, null).status());
        assertEquals(403, request("GET", "/enterprise/console/overview", login("COURIER"), null).status());
    }

    @Test void createStartFinishAndSameBoxExclusion() throws Exception {
        String operator = login("LOGISTICS_OPERATOR");
        var first = create(operator, "冷链样本", "EAST-01");
        assertEquals(200, first.status());
        long firstId = first.body().path("data").path("task").path("task_id").asLong();
        assertEquals("READY", first.body().path("data").path("task").path("status").asText());
        assertEquals(0, first.body().path("data").path("task").path("position").path("progress").asDouble());
        assertEquals(200, start(operator, firstId, false).status());
        assertEquals("ACTIVE", request("GET", "/enterprise/console/transports/" + firstId, operator, null).body().path("data").path("task").path("status").asText());
        var second = create(operator, "第二件", "EAST-02");
        long secondId = second.body().path("data").path("task").path("task_id").asLong();
        var conflict = start(operator, secondId, false);
        assertEquals(409, conflict.status());
        assertEquals("BOX_IN_USE", conflict.body().path("error").path("code").asText());
        var done = finish(operator, firstId);
        assertEquals("COMPLETED", done.body().path("data").path("task").path("status").asText());
        long ended = done.body().path("data").path("task").path("ended_at").asLong();
        assertEquals(ended, finish(operator, firstId).body().path("data").path("task").path("ended_at").asLong());
        assertEquals(200, start(operator, secondId, false).status());
        finish(operator, secondId);
    }

    @Test void riskConfirmationIsExplicit() throws Exception {
        String operator = login("LOGISTICS_OPERATOR");
        long id = create(operator, "风险样本", "NORTH-01").body().path("data").path("task").path("task_id").asLong();
        db.update("DELETE FROM alarm_state WHERE box_id='BOX-0001'");
        var risk = start(operator, id, false);
        assertEquals(409, risk.status());
        assertEquals("RISK_CONFIRMATION_REQUIRED", risk.body().path("error").path("code").asText());
        assertEquals(200, start(operator, id, true).status());
        finish(operator, id);
    }

    @Test void demoSeedCreatesSimulatedBoxHistoryAndAlarm() throws Exception {
        var seedJson = (ObjectNode) config.seed().deepCopy();
        String enterprise = seedJson.path("enterprises").get(0).path("name").asText();
        seedJson.putArray("simulated_boxes").addObject()
            .put("box_id", "BOX-SIM-TEST-01").put("display_name", "测试模拟箱").put("enterprise_name", enterprise)
            .put("env_node_id", "BOX-SIM-TEST-01-ENV").put("pose_node_id", "BOX-SIM-TEST-01-POSE")
            .put("env_required", true).put("pose_required", true).put("scenario", "normal");
        seedJson.putArray("history_tasks").addObject()
            .put("transport_no", "TR-00000000000000000009").put("box_id", "BOX-SIM-TEST-01").put("enterprise_name", enterprise)
            .put("item_name", "演示历史").put("route_code", "EAST-01")
            .put("started_offset_ms", 7200000).put("ended_offset_ms", 3600000);
        seedJson.putArray("demo_alarms").addObject()
            .put("box_id", "BOX-SIM-TEST-01").put("alarm_key", "TEST:env").put("type", "ENV_HIGH")
            .put("level", "WARNING").put("message", "测试活动告警").put("offset_ms", 600000);
        seed.initialize(seedJson);
        assertEquals("SIMULATED", db.queryForObject("SELECT asset_source FROM boxes WHERE box_id='BOX-SIM-TEST-01'", String.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM nodes WHERE box_id='BOX-SIM-TEST-01'", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM transport_tasks WHERE box_id='BOX-SIM-TEST-01' AND status='COMPLETED'", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM alarms WHERE box_id='BOX-SIM-TEST-01' AND active=1", Integer.class));
        String operator = login("LOGISTICS_OPERATOR");
        var overview = request("GET", "/enterprise/console/overview", operator, null);
        assertTrue(overview.body().path("data").path("boxes").toString().contains("BOX-SIM-TEST-01"));
        var alarms = request("GET", "/enterprise/console/alarms", operator, null);
        assertTrue(alarms.body().path("data").path("rows").toString().contains("TEST:env"));
    }

    private JsonNode createAsset(String operator, String name, String scenario) throws Exception {
        var body = json.createObjectNode().put("display_name", name).put("purpose", "演示用途").put("notes", "TASK-016 测试");
        if (scenario != null) body.put("scenario", scenario);
        var reply = request("POST", "/enterprise/console/boxes", operator, body);
        assertEquals(200, reply.status());
        return reply.body().path("data");
    }

    private void waitAlarm(String boxId, boolean active) throws Exception {
        for (int i = 0; i < 30; i++) {
            worker.runBatch();
            var rows = db.queryForList("SELECT assessment,active FROM alarm_state WHERE box_id=?", boxId);
            if (!rows.isEmpty()) {
                Object value = rows.getFirst().get("active");
                boolean current = Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0);
                if ("EVALUATED".equals(rows.getFirst().get("assessment").toString()) && current == active) return;
            }
            Thread.sleep(100);
        }
        fail("alarm state did not become " + active + " for " + boxId);
    }

    @Test void createEditAndScenarioAssets() throws Exception {
        String operator = login("LOGISTICS_OPERATOR");
        JsonNode created = createAsset(operator, "TASK-016 模拟箱", "normal");
        String boxId = created.path("box_id").asText();
        assertEquals("SIMULATED", created.path("asset_source").asText());
        assertEquals("normal", created.path("demo_scenario").asText());
        assertEquals(2, created.path("nodes").size());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM nodes WHERE box_id=?", Integer.class, boxId));

        var edited = request("POST", "/enterprise/console/boxes/" + boxId + "/edit", operator,
            json.createObjectNode().put("display_name", "TASK-016 改名").put("purpose", "新用途").put("notes", "新备注"));
        assertEquals(200, edited.status());
        assertEquals("TASK-016 改名", edited.body().path("data").path("display_name").asText());
        assertEquals("新用途", edited.body().path("data").path("purpose").asText());

        var realEdit = request("POST", "/enterprise/console/boxes/BOX-0001/edit", operator,
            json.createObjectNode().put("display_name", "实物箱改名").put("purpose", "实物用途"));
        assertEquals(200, realEdit.status());
        assertEquals("REAL", realEdit.body().path("data").path("asset_source").asText());
        assertEquals(409, request("POST", "/enterprise/console/boxes/BOX-0001/scenario", operator,
            json.createObjectNode().put("scenario", "offline")).status());

        var bad = request("POST", "/enterprise/console/boxes/" + boxId + "/scenario", operator,
            json.createObjectNode().put("scenario", "not-a-scene"));
        assertEquals(400, bad.status());
        assertEquals("INVALID_SCENARIO", bad.body().path("error").path("code").asText());
    }

    @Test void scenarioEnvHighCreatesAlarmAndNormalRecovers() throws Exception {
        String operator = login("LOGISTICS_OPERATOR");
        String boxId = createAsset(operator, "TASK-016 高温箱", "normal").path("box_id").asText();
        demoFleet.refresh(boxId);
        var high = request("POST", "/enterprise/console/boxes/" + boxId + "/scenario", operator,
            json.createObjectNode().put("scenario", "env_high"));
        assertEquals(200, high.status());
        assertEquals("env_high", high.body().path("data").path("demo_scenario").asText());
        waitAlarm(boxId, true);
        var normal = request("POST", "/enterprise/console/boxes/" + boxId + "/scenario", operator,
            json.createObjectNode().put("scenario", "normal"));
        assertEquals(200, normal.status());
        waitAlarm(boxId, false);
    }

    @Test void impactCreatesEventWithoutAlarm() throws Exception {
        String operator = login("LOGISTICS_OPERATOR");
        String boxId = createAsset(operator, "TASK-016 姿态箱", "normal").path("box_id").asText();
        demoFleet.refresh(boxId);
        assertEquals(200, request("POST", "/enterprise/console/boxes/" + boxId + "/scenario", operator,
            json.createObjectNode().put("scenario", "impact")).status());
        for (int i = 0; i < 10; i++) { worker.runBatch(); Thread.sleep(50); }
        var events = request("GET", "/enterprise/console/boxes/" + boxId + "/events", operator, null);
        assertEquals(200, events.status());
        assertTrue(events.body().path("data").toString().contains("IMPACT"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM alarms WHERE box_id=? AND active=1", Integer.class, boxId));
    }

    @Test void disableBlocksActiveTransportAndOfflineKeepsLastPosition() throws Exception {
        String operator = login("LOGISTICS_OPERATOR");
        String boxId = createAsset(operator, "TASK-016 停用箱", "normal").path("box_id").asText();
        demoFleet.refresh(boxId);
        var created = request("POST", "/enterprise/console/transports", operator,
            json.createObjectNode().put("box_id", boxId).put("item_name", "停用测试").put("route_code", "EAST-01"));
        long taskId = created.body().path("data").path("task").path("task_id").asLong();
        assertEquals(200, request("POST", "/enterprise/console/transports/" + taskId + "/start", operator,
            json.createObjectNode().put("confirm_risk", true)).status());
        var blocked = request("POST", "/enterprise/console/boxes/" + boxId + "/disable", operator, null);
        assertEquals(409, blocked.status());
        assertEquals("BOX_IN_USE", blocked.body().path("error").path("code").asText());
        request("POST", "/enterprise/console/transports/" + taskId + "/finish", operator, null);
        assertEquals(200, request("POST", "/enterprise/console/boxes/" + boxId + "/disable", operator, null).status());
        assertEquals(0, db.queryForObject("SELECT demo_enabled FROM boxes WHERE box_id=?", Integer.class, boxId));
        assertEquals(200, request("POST", "/enterprise/console/boxes/" + boxId + "/scenario", operator,
            json.createObjectNode().put("scenario", "offline")).status());
        demoFleet.refresh(boxId);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM node_runtime WHERE node_id IN " +
            "(SELECT node_id FROM nodes WHERE box_id=?) AND online=1", Integer.class, boxId));
    }

    @Test void scenarioStaleStopsLatestAndOfflineStopsRuntime() throws Exception {
        String operator = login("LOGISTICS_OPERATOR");
        String boxId = createAsset(operator, "TASK-016 过期箱", "normal").path("box_id").asText();
        demoFleet.refresh(boxId);
        assertEquals(200, request("POST", "/enterprise/console/boxes/" + boxId + "/scenario", operator,
            json.createObjectNode().put("scenario", "stale")).status());
        assertEquals("stale", db.queryForObject("SELECT demo_scenario FROM boxes WHERE box_id=?", String.class, boxId));
        demoFleet.refresh(boxId);
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM node_runtime WHERE node_id IN " +
            "(SELECT node_id FROM nodes WHERE box_id=?) AND online=1", Integer.class, boxId));

        assertEquals(200, request("POST", "/enterprise/console/boxes/" + boxId + "/scenario", operator,
            json.createObjectNode().put("scenario", "offline")).status());
        demoFleet.refresh(boxId);
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM node_runtime WHERE node_id IN " +
            "(SELECT node_id FROM nodes WHERE box_id=?) AND online=1", Integer.class, boxId));
    }

    @Test void detailTabsReadModels() throws Exception {
        String operator = login("LOGISTICS_OPERATOR");
        JsonNode created = createAsset(operator, "TASK-017 详情箱", "normal");
        String boxId = created.path("box_id").asText();
        demoFleet.refresh(boxId);
        var box = request("GET", "/enterprise/console/boxes/" + boxId, operator, null);
        assertEquals(200, box.status());
        assertTrue(box.body().path("data").path("pose").isObject());

        var node = db.queryForMap("SELECT node_id FROM nodes WHERE box_id=? AND role='ENV'", boxId);
        long now = System.currentTimeMillis();
        ObjectNode payload = json.createObjectNode();
        payload.put("v", 1); payload.put("box_id", boxId); payload.put("node_id", node.get("node_id").toString());
        payload.put("boot_id", 7); payload.put("ts", now); payload.put("ts_src", "SNTP");
        payload.put("uptime_ms", 1000); payload.put("time_uncertainty_ms", 100);
        payload.put("property_seq", 1); payload.put("role", "ENV"); payload.put("caps", 71);
        ObjectNode env = payload.putObject("env");
        env.put("temp_c", 4.8); env.put("humi_pct", 55.0); env.put("lux", 320);
        env.put("valid", true); env.put("light_on", false); env.put("fan_on", false);
        db.update("INSERT INTO telemetry(box_id,node_id,boot_id,property_seq,device_ts,ts_src,uptime_ms," +
                "time_uncertainty_ms,received_at,payload,created_at) VALUES(?,?,7,1,?, 'SNTP',1000,100,?,?,?)",
            boxId, node.get("node_id").toString(), now, now, json.writeValueAsString(payload), now);
        var samples = request("GET", "/enterprise/console/boxes/" + boxId + "/samples?kind=env&hours=1", operator, null);
        assertEquals(200, samples.status());
        assertTrue(samples.body().path("data").size() >= 1);
        assertEquals(4.8, samples.body().path("data").get(0).path("temperature").asDouble(), 0.001);

        var events = request("GET", "/enterprise/console/boxes/" + boxId + "/events?limit=50", operator, null);
        assertEquals(200, events.status());
        var alarms = request("GET", "/enterprise/console/boxes/" + boxId + "/alarms", operator, null);
        assertEquals(200, alarms.status());
        var logs = request("GET", "/enterprise/console/boxes/" + boxId + "/logs?limit=50", operator, null);
        assertEquals(200, logs.status());

        long operatorId = users.byUsername(account("LOGISTICS_OPERATOR").path("username").asText()).id();
        String operation = "op-task017-test";
        db.update("INSERT INTO command_operations(operation_id,box_id,requested_by,cmd,params,status,created_at,updated_at,deadline_at) " +
            "VALUES(?,?,?,?,?,'PENDING',?,?,?)", operation, boxId, operatorId, "output", "{\"kind\":\"light\",\"on\":true}", now, now, now + 15000);
        db.update("INSERT INTO node_commands(request_id,operation_id,box_id,node_id,target_caps,issued_at,expires_at,created_at,updated_at) " +
            "VALUES(?,?,?,?,2,?,?,?,?)", "req-task017-test", operation, boxId, node.get("node_id").toString(), now, now + 15000, now, now);
        var commands = request("GET", "/enterprise/console/boxes/" + boxId + "/commands", operator, null);
        assertEquals(200, commands.status());
        assertTrue(commands.body().path("data").toString().contains(operation));
    }

    @Test void commandSubmissionIdempotencyAndDemoGuard() throws Exception {
        String operator = login("LOGISTICS_OPERATOR");
        String boxId = createAsset(operator, "TASK-018 控制箱", "normal").path("box_id").asText();
        demoFleet.refresh(boxId);
        ObjectNode body = json.createObjectNode().put("cmd", "output");
        body.putObject("params").put("kind", "light").put("on", true).put("duration_s", 2);
        Map<String, String> headers = Map.of("Idempotency-Key", "task018-command-key");
        var submitted = request("POST", "/enterprise/console/boxes/" + boxId + "/commands", operator, body, headers);
        assertEquals(202, submitted.status());
        String operationId = submitted.body().path("data").path("operation_id").asText();
        assertFalse(operationId.isBlank());
        var status = request("GET", "/enterprise/console/boxes/" + boxId + "/commands/" + operationId, operator, null);
        assertEquals(200, status.status());
        assertEquals(operationId, status.body().path("data").path("operation_id").asText());
        assertEquals("PENDING", status.body().path("data").path("status").asText());
        assertEquals(1, status.body().path("data").path("nodes").size());

        var again = request("POST", "/enterprise/console/boxes/" + boxId + "/commands", operator, body, headers);
        assertEquals(202, again.status());
        assertEquals(operationId, again.body().path("data").path("operation_id").asText());

        ObjectNode changed = body.deepCopy();
        changed.withObject("params").put("duration_s", 3);
        var conflict = request("POST", "/enterprise/console/boxes/" + boxId + "/commands", operator, changed, headers);
        assertEquals(409, conflict.status());

        assertEquals(200, request("POST", "/enterprise/console/boxes/" + boxId + "/disable", operator, null).status());
        var disabled = request("POST", "/enterprise/console/boxes/" + boxId + "/commands", operator, body,
            Map.of("Idempotency-Key", "task018-disabled-key"));
        assertEquals(409, disabled.status());
        assertEquals("DEMO_DISABLED", disabled.body().path("error").path("code").asText());
    }

    @Test void editOnlyWhileReadyAndValidation() throws Exception {
        String operator = login("LOGISTICS_OPERATOR");
        long id = create(operator, "初始物品", "EAST-01").body().path("data").path("task").path("task_id").asLong();
        var edited = request("POST", "/enterprise/console/transports/" + id + "/edit", operator,
            json.createObjectNode().put("item_name", "修改后").put("route_code", "EAST-02"));
        assertEquals(200, edited.status());
        assertEquals("修改后", edited.body().path("data").path("task").path("item_name").asText());
        assertEquals("EAST-02", edited.body().path("data").path("task").path("route").path("code").asText());
        var badRoute = request("POST", "/enterprise/console/transports", operator,
            json.createObjectNode().put("box_id", "BOX-0001").put("item_name", "x").put("route_code", "NOPE"));
        assertEquals(400, badRoute.status());
        start(operator, id, false);
        var rejected = request("POST", "/enterprise/console/transports/" + id + "/edit", operator,
            json.createObjectNode().put("item_name", "不应保存").put("route_code", "EAST-01"));
        assertEquals(409, rejected.status());
        finish(operator, id);
    }
}
