package cn.smartbox.live;
import java.util.*;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.common.ApiEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/v1")
public class LiveController {
    private final QueryService query;private final CommandService commands;private final LiveWebSocket websocket;
    public LiveController(QueryService query,CommandService commands,LiveWebSocket websocket){this.query=query;this.commands=commands;this.websocket=websocket;}
    @GetMapping("/boxes") public Object boxes(@AuthenticationPrincipal CurrentUser user){return ApiEnvelope.success(query.boxes(user));}
    @GetMapping("/boxes/{id}/latest") public Object latest(@AuthenticationPrincipal CurrentUser user,@PathVariable String id){return ApiEnvelope.success(query.snapshot(user,id));}
    @GetMapping("/boxes/{id}/events") public Object events(@AuthenticationPrincipal CurrentUser user,@PathVariable String id,@RequestParam Map<String,String> params){return ApiEnvelope.success(query.history(user,id,params,false));}
    @GetMapping("/boxes/{id}/track") public Object track(@AuthenticationPrincipal CurrentUser user,@PathVariable String id,@RequestParam Map<String,String> params){return ApiEnvelope.success(query.history(user,id,params,true));}
    @GetMapping("/boxes/{id}/events/export") public ResponseEntity<String> export(@AuthenticationPrincipal CurrentUser user,@PathVariable String id,@RequestParam Map<String,String> params){return ResponseEntity.ok().contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8)).header("Content-Disposition","attachment; filename=events.csv").body(query.export(user,id,params));}
    @PostMapping("/boxes/{id}/commands") public ResponseEntity<Object> command(@AuthenticationPrincipal CurrentUser user,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){return ResponseEntity.accepted().body(ApiEnvelope.success(Map.of("operation_id",commands.submit(user,id,key,body))));}
    @GetMapping("/boxes/{id}/commands/{operation}") public Object operation(@AuthenticationPrincipal CurrentUser user,@PathVariable String id,@PathVariable String operation){return ApiEnvelope.success(commands.operation(user,id,operation));}
    @PostMapping("/ws-ticket") public Object ticket(@AuthenticationPrincipal CurrentUser user,@RequestHeader("Authorization")String authorization){return ApiEnvelope.success(websocket.ticket(user,authorization));}
}
