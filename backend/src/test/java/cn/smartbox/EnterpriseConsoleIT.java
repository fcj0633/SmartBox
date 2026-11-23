package cn.smartbox;

import cn.smartbox.asset.AssetBootstrapService;
import cn.smartbox.asset.DemoAssetConfig;
import cn.smartbox.auth.AuthMapper;
import cn.smartbox.config.BootstrapService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "smartbox.workers.enabled=false")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class EnterpriseConsoleIT {
    @Autowired JdbcTemplate db; @Autowired ObjectMapper json; @Autowired BootstrapService bootstrap;
    @Autowired AssetBootstrapService seed; @Autowired DemoAssetConfig config; @Autowired AuthMapper users;
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
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
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
