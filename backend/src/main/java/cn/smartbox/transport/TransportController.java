package cn.smartbox.transport;

import cn.smartbox.auth.CurrentUser;
import cn.smartbox.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Iterator;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/rider/transports")
public class TransportController {
    private final TransportService service;
    public TransportController(TransportService service){this.service=service;}
    private static long id(String raw){
        try{
            long value=Long.parseLong(raw);
            if(value<=0)throw ApiException.invalid();
            return value;
        }catch(NumberFormatException error){throw ApiException.invalid();}
    }
    @PostMapping public Object create(@AuthenticationPrincipal CurrentUser user,@RequestBody(required=false) JsonNode body){
        if(body==null||!body.isObject()||body.size()!=1||!body.path("box_id").isTextual())throw ApiException.invalid();
        return ApiEnvelope.success(service.create(user,body.get("box_id").asText()));
    }
    private static void onlyFields(JsonNode body,Set<String> allowed){
        for(Iterator<String> names=body.fieldNames();names.hasNext();)if(!allowed.contains(names.next()))throw ApiException.invalid();
    }
    @PostMapping("/{id}/item") public Object item(@AuthenticationPrincipal CurrentUser user,@PathVariable String id,@RequestBody(required=false) JsonNode body){
        if(body==null||!body.isObject()||!body.path("name").isTextual())throw ApiException.invalid();
        onlyFields(body,Set.of("name","description"));
        JsonNode description=body.get("description");
        if(description!=null&&!description.isNull()&&!description.isTextual())throw ApiException.invalid();
        return ApiEnvelope.success(service.saveItem(user,id(id),body.get("name").asText(),description==null||description.isNull()?null:description.asText()));
    }
    @PostMapping("/{id}/start") public Object start(@AuthenticationPrincipal CurrentUser user,@PathVariable String id,@RequestBody(required=false) JsonNode body){
        boolean confirm=false;
        if(body!=null&&!body.isNull()){
            if(!body.isObject())throw ApiException.invalid();
            onlyFields(body,Set.of("confirm_risk"));
            JsonNode value=body.get("confirm_risk");
            if(value!=null){if(!value.isBoolean())throw ApiException.invalid();confirm=value.asBoolean();}
        }
        return ApiEnvelope.success(service.start(user,id(id),confirm));
    }
    @PostMapping("/{id}/finish") public Object finish(@AuthenticationPrincipal CurrentUser user,@PathVariable String id){
        return ApiEnvelope.success(service.finish(user,id(id)));
    }
    @GetMapping public Object list(@AuthenticationPrincipal CurrentUser user){return ApiEnvelope.success(service.list(user));}
    @GetMapping("/{id}") public Object detail(@AuthenticationPrincipal CurrentUser user,@PathVariable String id){return ApiEnvelope.success(service.detail(user,id(id)));}
}
