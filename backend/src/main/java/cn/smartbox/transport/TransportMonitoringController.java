package cn.smartbox.transport;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class TransportMonitoringController {
 private final TransportMonitoringService monitoring;private final GuestAccessService guests;
 public TransportMonitoringController(TransportMonitoringService monitoring,GuestAccessService guests){this.monitoring=monitoring;this.guests=guests;}
 private static long id(String raw){try{long id=Long.parseLong(raw);if(id<=0)throw ApiException.invalid();return id;}catch(NumberFormatException invalid){throw ApiException.invalid();}}
 @GetMapping("/enterprise/riders") public Object riders(@AuthenticationPrincipal CurrentUser user){return ApiEnvelope.success(monitoring.riders(user));}
 @GetMapping("/enterprise/transports") public Object list(@AuthenticationPrincipal CurrentUser user){return ApiEnvelope.success(monitoring.list(user));}
 @GetMapping("/enterprise/transports/{id}") public Object detail(@AuthenticationPrincipal CurrentUser user,@PathVariable String id){return ApiEnvelope.success(monitoring.full(monitoring.enterpriseTask(user,id(id))));}
 @GetMapping("/enterprise/transports/{id}/monitoring") public Object enterprise(@AuthenticationPrincipal CurrentUser user,@PathVariable String id){return ApiEnvelope.success(monitoring.forEnterprise(user,id(id)));}
 @GetMapping("/rider/transports/{id}/monitoring") public Object rider(@AuthenticationPrincipal CurrentUser user,@PathVariable String id){return ApiEnvelope.success(monitoring.forRider(user,id(id)));}
 @PostMapping("/rider/transports/{id}/access-code") public Object code(@AuthenticationPrincipal CurrentUser user,@PathVariable String id){return ApiEnvelope.success(guests.issue(user,id(id)));}
 @PostMapping("/public/transport-access/verify") public Object verify(@RequestBody JsonNode body,HttpServletRequest request){
  if(!body.isObject()||body.size()!=2||!body.path("transport_no").isTextual()||!body.path("code").isTextual())throw ApiException.invalid();
  return ApiEnvelope.success(guests.verify(body.get("transport_no").asText(),body.get("code").asText(),request.getRemoteAddr()));
 }
 @GetMapping("/public/transport-view") public Object view(@RequestHeader(value="X-Transport-View-Token",required=false)String token,HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw ApiException.invalid();return ApiEnvelope.success(guests.view(token));}
}
