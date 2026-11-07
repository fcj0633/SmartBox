package cn.smartbox.live;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** SQL text is supplied only by application repository calls; values always use bindings. */
@Mapper
public interface LiveMapper {
    @SelectProvider(type=Sql.class,method="bound")
    List<Map<String,Object>> rows(@Param("sql")String sql,@Param("args")Object[] args);
    @SelectProvider(type=Sql.class,method="bound")
    long count(@Param("sql")String sql,@Param("args")Object[] args);
    @UpdateProvider(type=Sql.class,method="bound")
    int update(@Param("sql")String sql,@Param("args")Object[] args);

    class Sql {
        public static String bound(Map<String,Object> parameters){
            String sql=parameters.get("sql").toString();Object[] args=(Object[])parameters.get("args");
            StringBuilder result=new StringBuilder();int index=0;boolean quoted=false;
            for(int i=0;i<sql.length();i++){
                char c=sql.charAt(i);
                if(c=='\'')quoted=!quoted;
                if(c=='?'&&!quoted)result.append("#{args[").append(index++).append("]}");else result.append(c);
            }
            if(index!=args.length)throw new IllegalArgumentException("Repository binding mismatch");
            return result.toString();
        }
    }
}
