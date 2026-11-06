package cn.smartbox.common;
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    public ApiException(int status,String code,String message) { super(message);this.status=status;this.code=code; }
    public int status(){return status;}
    public String code(){return code;}
    public static ApiException invalid(){return new ApiException(400,"VALIDATION_ERROR","参数非法");}
    public static ApiException denied(){return new ApiException(403,"FORBIDDEN","无权访问");}
    public static ApiException credentials(){return new ApiException(401,"INVALID_CREDENTIALS","账号或密码错误");}
    public static ApiException conflict(){return new ApiException(409,"CONFLICT","初始化输入与现有数据不一致");}
}
