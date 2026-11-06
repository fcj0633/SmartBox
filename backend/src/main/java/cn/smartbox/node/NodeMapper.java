package cn.smartbox.node;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper
public interface NodeMapper {List<NodeRow> byBox(@Param("boxId") String boxId);}
