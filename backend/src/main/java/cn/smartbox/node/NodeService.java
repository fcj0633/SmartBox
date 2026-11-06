package cn.smartbox.node;
import java.util.List;
import cn.smartbox.auth.CurrentUser;
import cn.smartbox.authorization.*;
import org.springframework.stereotype.Service;
@Service
public class NodeService {
    private final BoxAccessService access;private final NodeMapper mapper;
    public NodeService(BoxAccessService access,NodeMapper mapper){this.access=access;this.mapper=mapper;}
    public record NodeView(String node_id,String role,int caps,boolean required,boolean online,Long boot_id,Long last_received_at){}
    public List<NodeView> list(CurrentUser user,String boxId){
        access.require(user,boxId,PermissionPolicy.Action.READ_DIAGNOSTICS);
        // TASK-005 supplies live connection state. This task initializes no received/boot values.
        return mapper.byBox(boxId).stream().map(n->new NodeView(n.nodeId(),n.role(),n.caps(),n.required(),false,n.currentBootId(),n.lastReceivedAt())).toList();
    }
}
