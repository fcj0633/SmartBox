package cn.smartbox.transport;
import java.time.Clock;
import java.util.*;
import cn.smartbox.auth.*;
import cn.smartbox.asset.*;
import cn.smartbox.common.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class TransportMonitoringService {
 public record Rider(long user_id,String username){}
 public record Task(long task_id,String transport_no,String status,String item_name,String item_description,Long started_at,Long ended_at){}
 public record Sample(long id,long observed_at,Double temperature,Double humidity,boolean valid,String time_quality){}
 public record Event(long id,long observed_at,String type,String level,String description){}
 public record Environment(String assessment,Double latest_temperature,Double latest_humidity,Long observed_at,String time_quality,String data_state){}
 public record Monitoring(Task task,Environment environment,List<Sample> samples,List<Event> events){}
 private final TransportMonitoringMapper store;private final TransportMapper tasks;private final AssetMapper assets;private final AuthMapper users;private final DemoAssetConfig config;private final ObjectMapper json;private final Clock clock;
 public TransportMonitoringService(TransportMonitoringMapper store,TransportMapper tasks,AssetMapper assets,AuthMapper users,DemoAssetConfig config,ObjectMapper json,Clock clock){this.store=store;this.tasks=tasks;this.assets=assets;this.users=users;this.config=config;this.json=json;this.clock=clock;}
 static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
 static Long nullable(Map<String,Object> row,String key){return row.get(key)==null?null:number(row,key);}
 static String text(Map<String,Object> row,String key){return row.get(key)==null?null:row.get(key).toString();}
 static ApiException missing(){return new ApiException(404,"NOT_FOUND","任务不存在或无权访问");}
 private void require(CurrentUser user,String role){var current=user==null?null:users.byId(user.id());if(current==null||!Boolean.TRUE.equals(current.enabled())||!role.equals(current.roleCode()))throw ApiException.denied();}
 private void enterprise(CurrentUser user){require(user,"LOGISTICS_OPERATOR");if(assets.enterprise(user.id())==null)throw ApiException.denied();}
 public List<Rider> riders(CurrentUser user){enterprise(user);return store.riders().stream().filter(r->config.allows(text(r,"username"))).map(r->new Rider(number(r,"id"),text(r,"username"))).toList();}
 public List<TransportView> list(CurrentUser user){enterprise(user);return store.enterpriseTasks(user.id()).stream().map(this::full).toList();}
 public Map<String,Object> enterpriseTask(CurrentUser user,long id){enterprise(user);var t=store.enterpriseTask(user.id(),id);if(t==null)throw missing();return t;}
 public Map<String,Object> riderTask(CurrentUser user,long id){require(user,"COURIER");var t=tasks.byIdForRider(id,user.id());if(t==null)throw missing();return t;}
 public TransportView full(Map<String,Object> t){return new TransportView(number(t,"id"),text(t,"transport_no"),text(t,"box_id"),text(t,"status"),text(t,"item_name"),text(t,"item_description"),nullable(t,"started_at"),nullable(t,"ended_at"),number(t,"created_at"),number(t,"updated_at"));}
 @Transactional(readOnly=true) public Monitoring forRider(CurrentUser user,long id){return project(riderTask(user,id));}
 @Transactional(readOnly=true) public Monitoring forEnterprise(CurrentUser user,long id){return project(enterpriseTask(user,id));}
 public Monitoring project(Map<String,Object> t){
  Task task=new Task(number(t,"id"),text(t,"transport_no"),text(t,"status"),text(t,"item_name"),text(t,"item_description"),nullable(t,"started_at"),nullable(t,"ended_at"));
  List<Sample> samples=new ArrayList<>();List<Event> events=new ArrayList<>();
  if(task.started_at()!=null){long end=task.ended_at()==null?clock.millis():task.ended_at();
   for(var row:store.samples(text(t,"box_id"),task.started_at(),end)){
    try{JsonNode env=json.readTree(text(row,"payload")).path("env");boolean valid=env.path("valid").asBoolean(false)&&env.path("temp_c").isNumber()&&env.path("humi_pct").isNumber();
     samples.add(new Sample(number(row,"id"),number(row,"device_ts"),valid?env.get("temp_c").doubleValue():null,valid?env.get("humi_pct").doubleValue():null,valid,"SNTP"));
    }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("Stored telemetry invalid");}
   }
   for(var row:store.events(text(t,"box_id"),task.started_at(),end))events.add(new Event(number(row,"id"),number(row,"device_ts"),text(row,"type"),text(row,"level"),description(text(row,"type"))));
  }
  Sample latest=samples.isEmpty()?null:samples.getFirst();
  Environment env=new Environment("UNKNOWN",latest==null?null:latest.temperature(),latest==null?null:latest.humidity(),latest==null?null:latest.observed_at(),latest==null?"UNKNOWN":"SNTP",task.started_at()==null?"READY":latest==null?"INSUFFICIENT":latest.valid()?"AVAILABLE":"INVALID");
  return new Monitoring(task,env,List.copyOf(samples),List.copyOf(events));
 }
 private static String description(String type){return switch(type){case "ENV_HIGH"->"环境值超过上限";case "ENV_LOW"->"环境值低于下限";case "ENV_RECOVERED"->"环境值恢复";case "IMPACT"->"设备检测到冲击";case "POSE_CHANGED"->"设备姿态变化";case "DOOR_EVIDENCE"->"设备开合证据";case "SENTRY_INTRUSION"->"哨兵异常提示";case "GEOFENCE_EXIT"->"离开模拟围栏";case "GEOFENCE_ENTER"->"返回模拟围栏";case "DEVICE_FAULT"->"设备故障";default->"设备状态记录";};}
}
