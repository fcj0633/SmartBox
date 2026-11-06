package cn.smartbox.auth;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface AuthMapper {
    UserAccount byUsername(@Param("username") String username);
    UserAccount byId(@Param("id") long id);
    List<String> boxes(@Param("id") long id);
    int hasBox(@Param("id") long id,@Param("boxId") String boxId);
    int boxExists(@Param("boxId") String boxId);
}
