package cn.smartbox.asset;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AssetController {
    private final AssetService assets;
    public AssetController(AssetService assets){this.assets=assets;}
    @GetMapping("/enterprise/boxes") public Object enterprise(@AuthenticationPrincipal CurrentUser user){return ApiEnvelope.success(assets.enterpriseBoxes(user));}
    @GetMapping("/rider/boxes") public Object rider(@AuthenticationPrincipal CurrentUser user){return ApiEnvelope.success(assets.riderBoxes(user));}
    @PostMapping("/enterprise/boxes/{boxId}/assign") public Object assign(@AuthenticationPrincipal CurrentUser user,@PathVariable String boxId,@RequestBody JsonNode body){
        if(!body.isObject()||body.size()!=1||!body.path("rider_user_id").isIntegralNumber()||!body.path("rider_user_id").canConvertToLong())throw ApiException.invalid();
        return ApiEnvelope.success(assets.assign(user,boxId,body.get("rider_user_id").longValue()));
    }
    @PostMapping("/enterprise/boxes/{boxId}/unassign") public Object unassign(@AuthenticationPrincipal CurrentUser user,@PathVariable String boxId){return ApiEnvelope.success(assets.unassign(user,boxId));}
}
