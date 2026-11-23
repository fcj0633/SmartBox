package cn.smartbox.enterprise;

import java.util.Map;
import java.util.Set;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Additive enterprise console API. Old /enterprise, /rider and /boxes endpoints are untouched. */
@RestController
@RequestMapping("/api/v1/enterprise/console")
public class EnterpriseConsoleController {
    private final EnterpriseReadService read;
    private final EnterpriseTransportService transports;
    private final EnterpriseAssetService assets;
    private final EnterpriseDetailService detail;
    private final EnterpriseControlService controls;

    public EnterpriseConsoleController(EnterpriseReadService read, EnterpriseTransportService transports,
                                       EnterpriseAssetService assets, EnterpriseDetailService detail,
                                       EnterpriseControlService controls) {
        this.read = read; this.transports = transports; this.assets = assets; this.detail = detail; this.controls = controls;
    }

    @GetMapping("/overview")
    public Object overview(@AuthenticationPrincipal CurrentUser user) { return ApiEnvelope.success(read.overview(user)); }

    @GetMapping("/boxes")
    public Object boxes(@AuthenticationPrincipal CurrentUser user) { return ApiEnvelope.success(read.boxes(user)); }

    @GetMapping("/boxes/{boxId}")
    public Object box(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId) {
        return ApiEnvelope.success(read.box(user, boxId));
    }

    @GetMapping("/boxes/{boxId}/events")
    public Object boxEvents(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId,
                            @RequestParam(required = false, defaultValue = "10") int limit) {
        return ApiEnvelope.success(detail.events(user, boxId, limit));
    }

    @GetMapping("/boxes/{boxId}/samples")
    public Object samples(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId,
                          @RequestParam(required = false, defaultValue = "env") String kind,
                          @RequestParam(required = false, defaultValue = "6") int hours) {
        return ApiEnvelope.success(detail.samples(user, boxId, kind, hours));
    }

    @GetMapping("/boxes/{boxId}/alarms")
    public Object boxAlarms(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId) {
        return ApiEnvelope.success(detail.alarms(user, boxId));
    }

    @GetMapping("/boxes/{boxId}/commands")
    public Object commands(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId) {
        return ApiEnvelope.success(detail.commands(user, boxId));
    }

    @PostMapping("/boxes/{boxId}/commands")
    public ResponseEntity<Object> command(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @RequestBody JsonNode body) {
        return ResponseEntity.accepted().body(ApiEnvelope.success(
            Map.of("operation_id", controls.submit(user, boxId, key, body))));
    }

    @GetMapping("/boxes/{boxId}/commands/{operationId}")
    public Object commandOperation(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId,
                                   @PathVariable String operationId) {
        return ApiEnvelope.success(controls.operation(user, boxId, operationId));
    }

    @GetMapping("/boxes/{boxId}/logs")
    public Object logs(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId,
                       @RequestParam(required = false, defaultValue = "50") int limit) {
        return ApiEnvelope.success(detail.logs(user, boxId, limit));
    }

    @GetMapping("/routes")
    public Object routes(@AuthenticationPrincipal CurrentUser user) {
        read.context(user);
        return ApiEnvelope.success(read.routes());
    }

    @GetMapping("/transports")
    public Object tasks(@AuthenticationPrincipal CurrentUser user) { return ApiEnvelope.success(read.tasks(user)); }

    @GetMapping("/transports/{id}")
    public Object task(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
        return ApiEnvelope.success(read.taskDetail(user, id(id)));
    }

    @PostMapping("/boxes")
    public Object createBox(@AuthenticationPrincipal CurrentUser user, @RequestBody(required = false) JsonNode body) {
        AssetFields fields = assetFields(body);
        return ApiEnvelope.success(assets.create(user, fields.displayName(), fields.purpose(), fields.notes(), fields.scenario()));
    }

    @PostMapping("/boxes/{boxId}/edit")
    public Object editBox(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId,
                          @RequestBody(required = false) JsonNode body) {
        AssetFields fields = assetFields(body);
        return ApiEnvelope.success(assets.edit(user, boxId, fields.displayName(), fields.purpose(), fields.notes()));
    }

    @PostMapping("/boxes/{boxId}/scenario")
    public Object scenario(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId,
                           @RequestBody(required = false) JsonNode body) {
        if (body == null || !body.isObject()) throw ApiException.invalid();
        onlyFields(body, Set.of("scenario"));
        JsonNode value = body.get("scenario");
        if (value == null || !value.isTextual()) throw ApiException.invalid();
        return ApiEnvelope.success(assets.scenario(user, boxId, value.asText()));
    }

    @PostMapping("/boxes/{boxId}/disable")
    public Object disable(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId) {
        return ApiEnvelope.success(assets.disable(user, boxId));
    }

    @PostMapping("/boxes/{boxId}/enable")
    public Object enable(@AuthenticationPrincipal CurrentUser user, @PathVariable String boxId) {
        return ApiEnvelope.success(assets.enable(user, boxId));
    }

    @PostMapping("/transports")
    public Object create(@AuthenticationPrincipal CurrentUser user, @RequestBody(required = false) JsonNode body) {
        Fields fields = fields(body, true);
        return ApiEnvelope.success(transports.create(user, fields.boxId(), fields.itemName(), fields.itemDescription(), fields.routeCode()));
    }

    @PostMapping("/transports/{id}/edit")
    public Object edit(@AuthenticationPrincipal CurrentUser user, @PathVariable String id, @RequestBody(required = false) JsonNode body) {
        Fields fields = fields(body, false);
        return ApiEnvelope.success(transports.edit(user, id(id), fields.itemName(), fields.itemDescription(), fields.routeCode()));
    }

    @PostMapping("/transports/{id}/start")
    public Object start(@AuthenticationPrincipal CurrentUser user, @PathVariable String id, @RequestBody(required = false) JsonNode body) {
        boolean confirm = false;
        if (body != null && !body.isNull()) {
            if (!body.isObject()) throw ApiException.invalid();
            onlyFields(body, Set.of("confirm_risk"));
            JsonNode value = body.get("confirm_risk");
            if (value != null) {
                if (!value.isBoolean()) throw ApiException.invalid();
                confirm = value.asBoolean();
            }
        }
        return ApiEnvelope.success(transports.start(user, id(id), confirm));
    }

    @PostMapping("/transports/{id}/finish")
    public Object finish(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
        return ApiEnvelope.success(transports.finish(user, id(id)));
    }

    @GetMapping("/alarms")
    public Object alarms(@AuthenticationPrincipal CurrentUser user) { return ApiEnvelope.success(read.alarms(user)); }

    private record Fields(String boxId, String itemName, String itemDescription, String routeCode) {}
    private record AssetFields(String displayName, String purpose, String notes, String scenario) {}

    private static AssetFields assetFields(JsonNode body) {
        if (body == null || !body.isObject()) throw ApiException.invalid();
        onlyFields(body, Set.of("display_name", "purpose", "notes", "scenario"));
        JsonNode name = body.get("display_name");
        if (name == null || !name.isTextual()) throw ApiException.invalid();
        JsonNode purpose = body.get("purpose"), notes = body.get("notes"), scenario = body.get("scenario");
        if ((purpose != null && !purpose.isNull() && !purpose.isTextual())
            || (notes != null && !notes.isNull() && !notes.isTextual())
            || (scenario != null && !scenario.isNull() && !scenario.isTextual()))
            throw ApiException.invalid();
        return new AssetFields(name.asText(),
            purpose == null || purpose.isNull() ? null : purpose.asText(),
            notes == null || notes.isNull() ? null : notes.asText(),
            scenario == null || scenario.isNull() ? null : scenario.asText());
    }

    private static Fields fields(JsonNode body, boolean requireBox) {
        if (body == null || !body.isObject()) throw ApiException.invalid();
        onlyFields(body, Set.of("box_id", "item_name", "item_description", "route_code"));
        JsonNode box = body.get("box_id"), item = body.get("item_name"), route = body.get("route_code");
        if ((requireBox && (box == null || !box.isTextual())) || (box != null && !box.isNull() && !box.isTextual())
            || item == null || !item.isTextual() || route == null || !route.isTextual())
            throw ApiException.invalid();
        JsonNode description = body.get("item_description");
        if (description != null && !description.isNull() && !description.isTextual()) throw ApiException.invalid();
        return new Fields(box == null || box.isNull() ? null : box.asText(), item.asText(),
            description == null || description.isNull() ? null : description.asText(), route.asText());
    }

    private static void onlyFields(JsonNode body, Set<String> allowed) {
        for (var names = body.fieldNames(); names.hasNext(); ) {
            if (!allowed.contains(names.next())) throw ApiException.invalid();
        }
    }

    private static long id(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) throw ApiException.invalid();
            return value;
        } catch (NumberFormatException error) {
            throw ApiException.invalid();
        }
    }
}
