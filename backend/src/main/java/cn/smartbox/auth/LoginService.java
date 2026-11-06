package cn.smartbox.auth;
import cn.smartbox.audit.AuditService;
import cn.smartbox.common.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.Map;
@Service
public class LoginService {
    private final AuthMapper mapper;private final PasswordEncoder passwords;private final TokenService tokens;private final AuditService audit;private final String dummy;
    public LoginService(AuthMapper mapper,PasswordEncoder passwords,TokenService tokens,AuditService audit){
        this.mapper=mapper;this.passwords=passwords;this.tokens=tokens;this.audit=audit;
        dummy=passwords.encode(java.util.UUID.randomUUID().toString());
    }
    public Map<String,Object> login(String username,String password){
        if(!Inputs.length(username,1,128)||!Inputs.length(password,1,256))throw ApiException.invalid();
        UserAccount account=mapper.byUsername(username);
        boolean match=passwords.matches(password,account==null?dummy:account.passwordHash());
        if(account==null||!Boolean.TRUE.equals(account.enabled())||!match){audit.record(account==null?null:account.id(),username,null,"LOGIN_FAILED","INVALID_CREDENTIALS");throw ApiException.credentials();}
        var token=tokens.issue(account.id());return Map.of("access_token",token.value(),"expires_at",token.expiresAt(),"role",account.roleCode());
    }
}
