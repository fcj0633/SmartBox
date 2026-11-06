package cn.smartbox.config;
import cn.smartbox.auth.*;
import cn.smartbox.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class BootstrapService {
    private final JdbcTemplate db;private final AuthMapper users;private final PasswordEncoder passwords;private final Clock clock;
    private static final Set<String> ROLES=Set.of("LOGISTICS_VIEWER","LOGISTICS_OPERATOR","DELIVERY_USER","COURIER");
    public BootstrapService(JdbcTemplate db,AuthMapper users,PasswordEncoder passwords,Clock clock){this.db=db;this.users=users;this.passwords=passwords;this.clock=clock;}
    private Set<String> strings(JsonNode array,boolean ids){
        if(array==null||!array.isArray())throw ApiException.invalid();
        Set<String> result=new TreeSet<>();
        for(JsonNode value:array)if(!value.isTextual()||(ids&&!Inputs.id(value.asText()))||!result.add(value.asText()))throw ApiException.invalid();
        return result;
    }
    @Transactional
    public void initialize(JsonNode input){
        Set<String> boxes=strings(input.get("boxes"),true);
        JsonNode accounts=input.get("users");if(accounts==null||!accounts.isArray()||boxes.isEmpty())throw ApiException.invalid();
        Set<String> names=new HashSet<>();
        for(JsonNode user:accounts){
            if(!user.path("username").isTextual()||!user.path("password").isTextual()||!Inputs.length(user.path("username").asText(),1,128)||!Inputs.length(user.path("password").asText(),1,256)
                ||!ROLES.contains(user.path("role").asText())||!names.add(user.path("username").asText())||!boxes.containsAll(strings(user.get("box_ids"),true)))throw ApiException.invalid();
        }
        long now=clock.millis();
        for(String box:boxes){
            db.update("INSERT INTO boxes(box_id,display_name,created_at,updated_at) SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM boxes WHERE box_id=?)",box,box,now,now,box);
            for(String role:List.of("ENV","POSE")){
                String node=box+"-"+role;if(!Inputs.id(node))throw ApiException.invalid();int caps=role.equals("ENV")?71:120;
                var existing=db.queryForList("SELECT box_id,role,caps,required FROM nodes WHERE node_id=?",node);
                if(existing.isEmpty())db.update("INSERT INTO nodes(node_id,box_id,role,caps,required,created_at,updated_at) VALUES(?,?,?,?,true,?,?)",node,box,role,caps,now,now);
                else {var n=existing.getFirst();if(!box.equals(n.get("box_id"))||!role.equals(n.get("role"))||((Number)n.get("caps")).intValue()!=caps||!Boolean.TRUE.equals(n.get("required")))throw ApiException.conflict();}
            }
        }
        for(JsonNode item:accounts){
            String name=item.get("username").asText(),password=item.get("password").asText(),role=item.get("role").asText();Set<String> grants=strings(item.get("box_ids"),true);
            UserAccount current=users.byUsername(name);
            if(current!=null){
                if(!role.equals(current.roleCode())||!Boolean.TRUE.equals(current.enabled())||!passwords.matches(password,current.passwordHash())||!grants.equals(new TreeSet<>(users.boxes(current.id()))))throw ApiException.conflict();
                continue;
            }
            db.update("INSERT INTO users(username,password_hash,role_code,created_at,updated_at) VALUES(?,?,?,?,?)",name,passwords.encode(password),role,now,now);
            long id=db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);
            for(String box:grants)db.update("INSERT INTO user_box_access(user_id,box_id,created_at) VALUES(?,?,?)",id,box,now);
        }
    }
}
