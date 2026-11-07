package cn.smartbox.auth;
import cn.smartbox.common.*;
import cn.smartbox.live.QueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final LoginService login;private final CurrentIdentityService identity;private final QueryService nodes;
    public ApiController(LoginService login,CurrentIdentityService identity,QueryService nodes){this.login=login;this.identity=identity;this.nodes=nodes;}
    @PostMapping("/auth/login") public Map<String,Object> login(@RequestBody com.fasterxml.jackson.databind.JsonNode input){
        if(!input.isObject()||!input.path("username").isTextual()||!input.path("password").isTextual())throw ApiException.invalid();
        return ApiEnvelope.success(login.login(input.get("username").asText(),input.get("password").asText()));
    }
    @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal CurrentUser user){return ApiEnvelope.success(identity.current(user));}
    @GetMapping("/boxes/{id}/nodes") public Map<String,Object> nodes(@AuthenticationPrincipal CurrentUser user,@PathVariable String id){return ApiEnvelope.success(nodes.nodes(user,id));}
}
