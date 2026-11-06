package cn.smartbox.common;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.Map;
@RestControllerAdvice
public class ApiAdvice {
    @ExceptionHandler(ApiException.class) public ResponseEntity<Map<String,Object>> domain(ApiException e){return ResponseEntity.status(e.status()).body(ApiEnvelope.error(e.code(),e.getMessage()));}
    @ExceptionHandler({DataAccessException.class,TransactionException.class}) public ResponseEntity<Map<String,Object>> database(Exception e){return ResponseEntity.status(503).body(ApiEnvelope.error("SERVICE_UNAVAILABLE","服务暂不可用"));}
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<Map<String,Object>> invalid(Exception e){return ResponseEntity.badRequest().body(ApiEnvelope.error("VALIDATION_ERROR","参数非法"));}
    @ExceptionHandler(NoResourceFoundException.class) public ResponseEntity<Map<String,Object>> missing(Exception e){return ResponseEntity.status(404).body(ApiEnvelope.error("NOT_FOUND","资源不存在"));}
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> unexpected(Exception e){return ResponseEntity.internalServerError().body(ApiEnvelope.error("INTERNAL_ERROR","服务暂不可用"));}
}
