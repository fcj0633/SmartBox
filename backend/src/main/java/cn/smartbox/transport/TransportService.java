package cn.smartbox.transport;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import java.util.HexFormat;
import cn.smartbox.asset.AssetService;
import cn.smartbox.audit.AuditService;
import cn.smartbox.auth.*;
import cn.smartbox.common.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TransportService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final TransportMapper transports;private final AssetService assets;private final AuthMapper users;
    private final AuditService audit;private final Clock clock;
    public TransportService(TransportMapper transports,AssetService assets,AuthMapper users,AuditService audit,Clock clock){
        this.transports=transports;this.assets=assets;this.users=users;this.audit=audit;this.clock=clock;
    }
    private void requireCourier(CurrentUser user){
        UserAccount current=user==null?null:users.byId(user.id());
        if(current==null||!Boolean.TRUE.equals(current.enabled())||!"COURIER".equals(current.roleCode()))throw ApiException.denied();
    }
    private static Long number(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:((Number)v).longValue();}
    private static String text(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:v.toString();}
    private static ApiException notFound(){return new ApiException(404,"NOT_FOUND","任务不存在");}
    private static ApiException invalidState(){return new ApiException(409,"INVALID_TASK_STATE","当前任务状态不允许该操作");}
    private TransportView view(Map<String,Object> row){
        return new TransportView(number(row,"id"),text(row,"transport_no"),text(row,"box_id"),text(row,"status"),
            text(row,"item_name"),text(row,"item_description"),number(row,"started_at"),number(row,"ended_at"),
            number(row,"created_at"),number(row,"updated_at"));
    }
    private void afterCommit(CurrentUser user,String box,String action){
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){audit.record(user.id(),null,box,action,"SUCCEEDED");}
        });
    }
    private static String itemName(String raw){
        if(raw==null)throw ApiException.invalid();
        String value=raw.strip();
        if(!Inputs.length(value,1,50))throw ApiException.invalid();
        return value;
    }
    private static String itemDescription(String raw){
        if(raw==null)return null;
        String value=raw.strip();
        if(value.isEmpty())return null;
        if(!Inputs.length(value,1,200))throw ApiException.invalid();
        return value;
    }
    private static String nextTransportNo(){
        byte[] bytes=new byte[10];RANDOM.nextBytes(bytes);
        return "TR-"+HexFormat.of().withUpperCase().formatHex(bytes);
    }
    @Transactional public TransportView create(CurrentUser user,String boxId){
        requireCourier(user);
        if(!Inputs.id(boxId))throw ApiException.invalid();
        assets.requireRiderUse(user,boxId);
        long now=clock.millis();String number=nextTransportNo();
        try{transports.insert(number,boxId,user.id(),now);}
        catch(DuplicateKeyException conflict){throw new ApiException(409,"TRANSPORT_NO_CONFLICT","运输编号冲突，请重试");}
        Map<String,Object> row=transports.byTransportNo(number);
        if(row==null)throw new ApiException(409,"CONFLICT","运输任务创建失败");
        afterCommit(user,boxId,"TRANSPORT_CREATE");
        return view(row);
    }
    @Transactional public TransportView saveItem(CurrentUser user,long id,String name,String description){
        requireCourier(user);
        String item=itemName(name),detail=itemDescription(description);
        Map<String,Object> task=transports.byIdForRider(id,user.id());
        if(task==null)throw notFound();
        if(!"READY".equals(text(task,"status")))throw invalidState();
        String box=text(task,"box_id");
        assets.requireRiderUse(user,box);
        int updated=transports.updateItem(id,user.id(),item,detail,clock.millis());
        if(updated==0){
            Map<String,Object> current=transports.byIdForRider(id,user.id());
            if(current==null)throw notFound();
            throw invalidState();
        }
        afterCommit(user,box,"TRANSPORT_ITEM");
        return view(transports.byIdForRider(id,user.id()));
    }
    public List<TransportView> list(CurrentUser user){
        requireCourier(user);
        return transports.listForRider(user.id(),50).stream().map(this::view).toList();
    }
    public TransportView detail(CurrentUser user,long id){
        requireCourier(user);
        Map<String,Object> task=transports.byIdForRider(id,user.id());
        if(task==null)throw notFound();
        return view(task);
    }
    private boolean online(String box,long now){
        Map<String,Object> status=transports.nodeStatus(box,now-90000);
        if(status==null)return false;
        Long required=number(status,"required_count"),online=number(status,"online_count");
        return required!=null&&required>0&&online!=null&&online.longValue()==required.longValue();
    }
    private boolean riskRequired(String box){
        Map<String,Object> state=transports.alarmState(box);
        if(state==null)return true;
        if(!"EVALUATED".equals(text(state,"assessment")))return true;
        Object active=state.get("active");
        if(active instanceof Boolean value)return value;
        return active instanceof Number value&&value.intValue()!=0;
    }
    @Transactional public TransportView start(CurrentUser user,long id,boolean confirmRisk){
        requireCourier(user);
        Map<String,Object> task=transports.byIdForRider(id,user.id());
        if(task==null)throw notFound();
        String status=text(task,"status");
        if("ACTIVE".equals(status))return view(task);
        if(!"READY".equals(status))throw invalidState();
        String box=text(task,"box_id");
        assets.requireRiderUseForUpdate(user,box);
        Map<String,Object> locked=transports.byIdForUpdate(id);
        if(locked==null||!Objects.equals(number(locked,"rider_id"),user.id()))throw notFound();
        status=text(locked,"status");
        if("ACTIVE".equals(status))return view(locked);
        if(!"READY".equals(status))throw invalidState();
        if(text(locked,"item_name")==null||text(locked,"item_name").isBlank())throw new ApiException(400,"VALIDATION_ERROR","请先填写物品");
        long now=clock.millis();
        if(!online(box,now))throw new ApiException(409,"BOX_OFFLINE","箱体必需节点不在线");
        boolean risk=riskRequired(box);
        if(risk&&!confirmRisk)throw new ApiException(409,"RISK_CONFIRMATION_REQUIRED","存在未知或活动告警，请确认后重试");
        try{
            int updated=transports.start(id,user.id(),now);
            if(updated==0){
                Map<String,Object> current=transports.byIdForRider(id,user.id());
                if(current!=null&&"ACTIVE".equals(text(current,"status")))return view(current);
                throw invalidState();
            }
        }catch(DuplicateKeyException conflict){throw new ApiException(409,"BOX_IN_USE","该箱体已在运输中");}
        if(risk)afterCommit(user,box,"TRANSPORT_RISK_CONFIRM");
        afterCommit(user,box,"TRANSPORT_START");
        return view(transports.byIdForRider(id,user.id()));
    }
    @Transactional public TransportView finish(CurrentUser user,long id){
        requireCourier(user);
        Map<String,Object> task=transports.byIdForRider(id,user.id());
        if(task==null)throw notFound();
        String status=text(task,"status");
        if("COMPLETED".equals(status))return view(task);
        if(!"ACTIVE".equals(status))throw invalidState();
        String box=text(task,"box_id");
        assets.lockOwnershipForUpdate(box);
        Map<String,Object> locked=transports.byIdForUpdate(id);
        if(locked==null||!Objects.equals(number(locked,"rider_id"),user.id()))throw notFound();
        status=text(locked,"status");
        if("COMPLETED".equals(status))return view(locked);
        if(!"ACTIVE".equals(status))throw invalidState();
        int updated=transports.finish(id,user.id(),clock.millis());
        if(updated==0){
            Map<String,Object> current=transports.byIdForRider(id,user.id());
            if(current!=null&&"COMPLETED".equals(text(current,"status")))return view(current);
            throw invalidState();
        }
        afterCommit(user,box,"TRANSPORT_FINISH");
        return view(transports.byIdForRider(id,user.id()));
    }
}
