package cn.smartbox.common;
import java.util.Map;
import org.slf4j.MDC;
public final class ApiEnvelope {
    private ApiEnvelope(){}
    public static String requestId(){String id=MDC.get("request_id");return id==null?java.util.UUID.randomUUID().toString():id;}
    public static Map<String,Object> success(Object data){return Map.of("data",data,"request_id",requestId());}
    public static Map<String,Object> error(String code,String message){return Map.of("error",Map.of("code",code,"message",message),"request_id",requestId());}
}
