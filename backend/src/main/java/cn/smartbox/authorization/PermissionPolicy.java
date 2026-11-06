package cn.smartbox.authorization;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.common.ApiException;
import java.util.Set;
import org.springframework.stereotype.Component;
@Component
public class PermissionPolicy {
    public enum Action {READ_BUSINESS,READ_DIAGNOSTICS,SENTRY_CONTROL,CONFIGURE,EXPORT}
    private static final Set<String> ROLES=Set.of("LOGISTICS_VIEWER","LOGISTICS_OPERATOR","DELIVERY_USER","COURIER");
    public boolean allowed(CurrentUser user,Action action) {
        if(user==null||!ROLES.contains(user.role()))return false;
        return switch(action){
            case READ_BUSINESS->true;
            case READ_DIAGNOSTICS->!user.role().equals("DELIVERY_USER");
            case SENTRY_CONTROL->Set.of("LOGISTICS_OPERATOR","COURIER").contains(user.role());
            case CONFIGURE,EXPORT->user.role().equals("LOGISTICS_OPERATOR");
        };
    }
    public void require(CurrentUser user,Action action){if(!allowed(user,action))throw ApiException.denied();}
}
