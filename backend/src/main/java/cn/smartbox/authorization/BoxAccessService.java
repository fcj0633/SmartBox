package cn.smartbox.authorization;
import cn.smartbox.auth.*;
import cn.smartbox.common.*;
import cn.smartbox.audit.AuditService;
import org.springframework.stereotype.Service;
@Service
public class BoxAccessService {
    private final AuthMapper mapper;private final PermissionPolicy policy;private final AuditService audit;
    public BoxAccessService(AuthMapper mapper,PermissionPolicy policy,AuditService audit){this.mapper=mapper;this.policy=policy;this.audit=audit;}
    public void require(CurrentUser user,String boxId,PermissionPolicy.Action action) {
        if(!Inputs.id(boxId))throw ApiException.invalid();
        if(!policy.allowed(user,action)||mapper.hasBox(user.id(),boxId)==0){
            audit.record(user.id(),null,boxId,"ACCESS_DENIED","FORBIDDEN");throw ApiException.denied();
        }
        if(mapper.boxExists(boxId)==0)throw new ApiException(404,"NOT_FOUND","资源不存在");
    }
}
