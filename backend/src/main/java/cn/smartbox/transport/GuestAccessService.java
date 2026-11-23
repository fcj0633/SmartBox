package cn.smartbox.transport;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.common.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class GuestAccessService {
 public record Code(String transport_no,String code,Long valid_until){}
 public record Session(String token,long expires_at){}
 private static final long WINDOW=900000L,POST_END=7*86400000L;
 private static final SecureRandom RANDOM=new SecureRandom();
 private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(10);
 private final TransportMapper tasks;private final TransportMonitoringMapper store;private final TransportMonitoringService monitoring;private final Clock clock;
 private final Map<String,Failure> failures=new HashMap<>();
 private record Failure(int count,long until){}
 private final String dummy=encoder.encode("not-a-valid-code");
 public GuestAccessService(TransportMapper tasks,TransportMonitoringMapper store,TransportMonitoringService monitoring,Clock clock){this.tasks=tasks;this.store=store;this.monitoring=monitoring;this.clock=clock;}
 static byte[] digest(String raw){try{return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 private static ApiException denied(){return new ApiException(401,"INVALID_TRANSPORT_ACCESS","运输编号或查看凭据无效或已过期");}
 private static long validUntil(Map<String,Object> task){
  if(task==null||!Set.of("ACTIVE","COMPLETED").contains(TransportMonitoringService.text(task,"status")))return 0;
  Long end=TransportMonitoringService.nullable(task,"ended_at");return end==null?Long.MAX_VALUE:Math.addExact(end,POST_END);
 }
 private synchronized void checkLimits(String no,String ip,long now){
  failures.entrySet().removeIf(e->e.getValue().until()<=now);
  if(failures.size()>=4096)throw limited();
  for(String key:keys(no,ip)){Failure f=failures.get(key);if(f!=null&&f.count()>=5)throw limited();}
 }
 private static ApiException limited(){return new ApiException(429,"ACCESS_RATE_LIMITED","尝试次数过多，请稍后重试");}
 private static List<String> keys(String no,String ip){return List.of("ip:"+ip,"task:"+no+":"+ip);}
 private synchronized void failed(String no,String ip,long now){for(String key:keys(no,ip))failures.compute(key,(k,f)->f==null?new Failure(1,now+WINDOW):new Failure(f.count()+1,f.until()));}
 @Transactional public Code issue(CurrentUser user,long id){
  monitoring.riderTask(user,id);var task=tasks.byIdForUpdate(id);long until=validUntil(task),now=clock.millis();
  if(now>=until)throw new ApiException(409,"INVALID_TASK_STATE","任务尚未开始或查看期限已结束");
  String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";StringBuilder code=new StringBuilder();for(int i=0;i<10;i++)code.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
  store.code(id,encoder.encode(code),now);return new Code(TransportMonitoringService.text(task,"transport_no"),code.toString(),until==Long.MAX_VALUE?null:until);
 }
 @Transactional public Session verify(String no,String code,String ip){
  if(no==null||code==null||no.length()>64||code.length()>64)throw denied();long now=clock.millis();checkLimits(no,ip,now);
  var task=no.matches("TR-[0-9A-F]{20}")?tasks.byTransportNo(no):null;Map<String,Object> access=null;
  if(task!=null){task=tasks.byIdForUpdate(TransportMonitoringService.number(task,"id"));access=store.accessForUpdate(TransportMonitoringService.number(task,"id"));}
  boolean matches=encoder.matches(code,access==null?dummy:TransportMonitoringService.text(access,"code_hash"));
  if(access==null||!matches||now>=validUntil(task)){failed(no,ip,now);throw denied();}
  byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);long expires=Math.min(now+1800000L,validUntil(task));
  store.sessionSet(TransportMonitoringService.number(task,"id"),digest(token),expires);return new Session(token,expires);
 }
 @Transactional(readOnly=true) public TransportMonitoringService.Monitoring view(String token){
  if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw denied();var session=store.session(digest(token));long now=clock.millis();
  if(session==null||now>=TransportMonitoringService.number(session,"session_expires_at"))throw denied();var task=tasks.byId(TransportMonitoringService.number(session,"task_id"));
  if(now>=validUntil(task))throw denied();return monitoring.project(task);
 }
}
