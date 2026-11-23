package cn.smartbox.transport;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface TransportMonitoringMapper {
 List<Map<String,Object>> enterpriseTasks(@Param("user")long user);
 Map<String,Object> enterpriseTask(@Param("user")long user,@Param("id")long id);
 List<Map<String,Object>> samples(@Param("box")String box,@Param("start")long start,@Param("end")long end);
 List<Map<String,Object>> events(@Param("box")String box,@Param("start")long start,@Param("end")long end);
 List<Map<String,Object>> riders();
 Map<String,Object> accessForUpdate(@Param("id")long id);
 Map<String,Object> session(@Param("hash")byte[] hash);
 void code(@Param("id")long id,@Param("hash")String hash,@Param("now")long now);
 void sessionSet(@Param("id")long id,@Param("hash")byte[] hash,@Param("expires")long expires);
}
