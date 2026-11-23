package cn.smartbox.asset;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AssetMapper {
    Map<String,Object> enterprise(@Param("userId") long userId);
    List<Map<String,Object>> enterpriseBoxes(@Param("enterpriseId") long enterpriseId);
    Map<String,Object> lockOwnership(@Param("boxId") String boxId);
    Map<String,Object> assignment(@Param("boxId") String boxId);
    int assign(@Param("boxId") String boxId,@Param("riderId") long riderId,@Param("actorId") long actorId,@Param("now") long now);
    int unassign(@Param("boxId") String boxId);
    List<Map<String,Object>> riderBoxes(@Param("userId") long userId);
    int canUse(@Param("userId") long userId,@Param("boxId") String boxId);
}
