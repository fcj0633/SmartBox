package cn.smartbox.transport;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TransportMapper {
    int insert(@Param("transportNo")String transportNo,@Param("boxId")String boxId,@Param("riderId")long riderId,@Param("now")long now);
    Map<String,Object> byTransportNo(@Param("transportNo")String transportNo);
    Map<String,Object> byId(@Param("id")long id);
    Map<String,Object> byIdForUpdate(@Param("id")long id);
    Map<String,Object> byIdForRider(@Param("id")long id,@Param("riderId")long riderId);
    List<Map<String,Object>> listForRider(@Param("riderId")long riderId,@Param("limit")int limit);
    int updateItem(@Param("id")long id,@Param("riderId")long riderId,@Param("name")String name,@Param("description")String description,@Param("now")long now);
    int start(@Param("id")long id,@Param("riderId")long riderId,@Param("now")long now);
    int finish(@Param("id")long id,@Param("riderId")long riderId,@Param("now")long now);
    Map<String,Object> activeForUpdate(@Param("boxId")String boxId);
    Map<String,Object> nodeStatus(@Param("boxId")String boxId,@Param("since")long since);
    Map<String,Object> alarmState(@Param("boxId")String boxId);
}
