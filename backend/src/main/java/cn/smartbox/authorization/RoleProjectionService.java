package cn.smartbox.authorization;
import cn.smartbox.auth.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.util.Set;
@Service
public class RoleProjectionService {
    private final PermissionPolicy policy;
    public RoleProjectionService(PermissionPolicy policy){this.policy=policy;}
    /** Contract-defined view only; no actual aggregation endpoint is implemented here. */
    public record DeliveryView(String box_id,int online_mask,String connectivity,JsonNode alarm,JsonNode door,JsonNode env,JsonNode loc){}
    private JsonNode pick(JsonNode source,String... names){
        ObjectNode out=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if(source!=null&&source.isObject())for(String name:names)if(source.has(name))out.set(name,source.get(name).deepCopy());
        return out;
    }
    private JsonNode sample(JsonNode source,String... values) {
        ObjectNode out=(ObjectNode)pick(source,"valid","observed_at","received_at","stale","src","time_quality");
        if(source!=null&&source.has("value"))out.set("value",source.get("value").isNull()?source.get("value"):pick(source.get("value"),values));
        return out;
    }
    public DeliveryView delivery(CurrentUser user,JsonNode snapshot) {
        policy.require(user,PermissionPolicy.Action.READ_BUSINESS);
        if(!user.role().equals("DELIVERY_USER"))throw cn.smartbox.common.ApiException.denied();
        return new DeliveryView(snapshot.path("box_id").asText(),snapshot.path("online_mask").asInt(),snapshot.path("connectivity").asText(),
            pick(snapshot.get("alarm"),"active","level"),pick(snapshot.get("door"),"state","confidence","conclusion_id","observed_at","historical"),
            sample(snapshot.get("env"),"temp_c","humi_pct","lux","light_on","fan_on","valid"),sample(snapshot.get("loc"),"lat","lon","valid","src"));
    }
}
