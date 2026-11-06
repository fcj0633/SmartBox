package cn.smartbox.common;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;
@Component("smartboxRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    public RequestContextFilter(ObjectMapper json){this.json=json;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        MDC.put("request_id",java.util.UUID.randomUUID().toString());res.setHeader("X-Request-Id",ApiEnvelope.requestId());
        try{chain.doFilter(req,res);}
        catch(ApiException e){write(res,json,e.status(),e.code(),e.getMessage());}
        catch(DataAccessException|TransactionException e){write(res,json,503,"SERVICE_UNAVAILABLE","服务暂不可用");}
        catch(Exception unexpected){
            Throwable cause=unexpected;
            while(cause.getCause()!=null&&cause!=cause.getCause())cause=cause.getCause();
            int status=cause instanceof java.sql.SQLException?503:500;
            write(res,json,status,status==503?"SERVICE_UNAVAILABLE":"INTERNAL_ERROR","服务暂不可用");
        }finally{MDC.remove("request_id");}
    }
    public static void write(HttpServletResponse res,ObjectMapper json,int status,String code,String message)throws IOException {
        if(res.isCommitted())return;
        res.resetBuffer();res.setStatus(status);res.setCharacterEncoding("UTF-8");res.setContentType("application/json");json.writeValue(res.getOutputStream(),ApiEnvelope.error(code,message));
    }
}
