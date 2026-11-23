package cn.smartbox.enterprise;

import java.time.Clock;
import com.fasterxml.jackson.databind.JsonNode;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.common.ApiException;
import cn.smartbox.enterprise.EnterpriseViews.Box;
import cn.smartbox.live.CommandService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Enterprise-scoped command facade. Device execution stays in the existing command service;
 * this class only proves enterprise ownership and supplies the access relation used by legacy
 * command endpoints. Access is derived from ownership here, never the other way around.
 */
@Service
public class EnterpriseControlService {
    private final EnterpriseReadService read;
    private final CommandService commands;
    private final JdbcTemplate db;
    private final Clock clock;

    public EnterpriseControlService(EnterpriseReadService read, CommandService commands, JdbcTemplate db, Clock clock) {
        this.read = read; this.commands = commands; this.db = db; this.clock = clock;
    }

    public String submit(CurrentUser user, String boxId, String key, JsonNode body) {
        Box box = read.box(user, boxId);
        if ("SIMULATED".equals(box.asset_source()) && !box.demo_enabled())
            throw new ApiException(409, "DEMO_DISABLED", "模拟输入已停用，不能提交控制命令");
        grantAccess(user, boxId);
        return commands.submit(user, boxId, key, body);
    }

    public JsonNode operation(CurrentUser user, String boxId, String operationId) {
        read.box(user, boxId);
        grantAccess(user, boxId);
        return commands.operation(user, boxId, operationId);
    }

    private void grantAccess(CurrentUser user, String boxId) {
        db.update("INSERT IGNORE INTO user_box_access(user_id,box_id,created_at) VALUES(?,?,?)",
            user.id(), boxId, clock.millis());
    }
}
