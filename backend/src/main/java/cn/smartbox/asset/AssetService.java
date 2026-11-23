package cn.smartbox.asset;

import java.time.Clock;
import java.util.*;
import cn.smartbox.auth.*;
import cn.smartbox.common.*;
import cn.smartbox.audit.AuditService;
import cn.smartbox.transport.TransportMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
public class AssetService {
    public record EnterpriseBox(String box_id,String display_name,Long rider_user_id,String rider_username,Long assigned_at){}
    public record EnterpriseBoxes(long enterprise_id,String enterprise_name,List<EnterpriseBox> boxes){}
    public record RiderBox(String box_id,String display_name,String source,String enterprise_name){}
    public record Assignment(String box_id,long rider_user_id,long assigned_at){}
    public record Unassigned(String box_id,boolean assigned){}
    private final AssetMapper assets;private final AuthMapper users;private final DemoAssetConfig config;private final AuditService audit;private final Clock clock;private final TransportMapper transports;
    public AssetService(AssetMapper assets,AuthMapper users,DemoAssetConfig config,AuditService audit,Clock clock,TransportMapper transports){this.assets=assets;this.users=users;this.config=config;this.audit=audit;this.clock=clock;this.transports=transports;}
    private UserAccount require(CurrentUser user,String role){
        UserAccount current=user==null?null:users.byId(user.id());
        if(current==null||!Boolean.TRUE.equals(current.enabled())||!role.equals(current.roleCode()))throw ApiException.denied();
        return current;
    }
    private Map<String,Object> enterprise(CurrentUser user){require(user,"LOGISTICS_OPERATOR");var e=assets.enterprise(user.id());if(e==null)throw ApiException.denied();return e;}
    private static Long number(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:((Number)v).longValue();}
    private static String text(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:v.toString();}
    public EnterpriseBoxes enterpriseBoxes(CurrentUser user){
        var e=enterprise(user);long id=number(e,"id");
        return new EnterpriseBoxes(id,text(e,"name"),assets.enterpriseBoxes(id).stream().map(r->new EnterpriseBox(text(r,"box_id"),text(r,"display_name"),number(r,"rider_user_id"),text(r,"rider_username"),number(r,"assigned_at"))).toList());
    }
    public List<RiderBox> riderBoxes(CurrentUser user){
        require(user,"COURIER");return assets.riderBoxes(user.id()).stream().map(r->new RiderBox(text(r,"box_id"),text(r,"display_name"),text(r,"source"),text(r,"enterprise_name"))).toList();
    }
    /** TASK-012 must call this again in its own state-transition transaction. */
    public void requireRiderUse(CurrentUser user,String boxId){require(user,"COURIER");if(!Inputs.id(boxId))throw ApiException.invalid();if(assets.canUse(user.id(),boxId)!=1)throw ApiException.denied();}
    /** Locked current-read variant for a transport state transition; caller must own the transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireRiderUseForUpdate(CurrentUser user,String boxId){
        require(user,"COURIER");
        if(!Inputs.id(boxId))throw ApiException.invalid();
        var ownership=assets.lockOwnership(boxId);
        if(ownership==null)throw ApiException.denied();
        String type=text(ownership,"owner_type");
        if("PERSONAL".equals(type)){if(!Objects.equals(number(ownership,"owner_user_id"),user.id()))throw ApiException.denied();}
        else if("ENTERPRISE".equals(type)){var assignment=assets.assignment(boxId);if(assignment==null||!Objects.equals(number(assignment,"rider_user_id"),user.id()))throw ApiException.denied();}
        else throw ApiException.denied();
    }
    /** Lock only the ownership row, preserving ownership -> transport lock order for finish. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void lockOwnershipForUpdate(String boxId){if(!Inputs.id(boxId))throw ApiException.invalid();assets.lockOwnership(boxId);}
    private void requireOwned(CurrentUser user,String box){
        var e=enterprise(user);if(!Inputs.id(box))throw ApiException.invalid();var o=assets.lockOwnership(box);
        if(o==null||!"ENTERPRISE".equals(o.get("owner_type"))||!Objects.equals(number(o,"enterprise_id"),number(e,"id")))throw ApiException.denied();
    }
    private void afterCommit(CurrentUser user,String box,String action){
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){audit.record(user.id(),null,box,action,"SUCCEEDED");}});
    }
    @Transactional public Assignment assign(CurrentUser user,String box,long riderId){
        requireOwned(user,box);if(riderId<=0)throw ApiException.invalid();
        UserAccount rider=users.byId(riderId);
        if(rider==null||!Boolean.TRUE.equals(rider.enabled())||!"COURIER".equals(rider.roleCode())||!config.allows(rider.username()))throw ApiException.invalid();
        var prior=assets.assignment(box);
        if(prior!=null){if(number(prior,"rider_user_id")!=riderId)throw new ApiException(409,"BOX_ALREADY_ASSIGNED","该箱体已分配给其他骑手");return new Assignment(box,riderId,number(prior,"assigned_at"));}
        long now=clock.millis();assets.assign(box,riderId,user.id(),now);afterCommit(user,box,"ASSET_ASSIGN");return new Assignment(box,riderId,now);
    }
    @Transactional public Unassigned unassign(CurrentUser user,String box){
        requireOwned(user,box);
        if(transports.activeForUpdate(box)!=null)throw new ApiException(409,"BOX_IN_USE","该箱体正在运输中，不能收回");
        if(assets.unassign(box)>0)afterCommit(user,box,"ASSET_UNASSIGN");
        return new Unassigned(box,false);
    }
}
