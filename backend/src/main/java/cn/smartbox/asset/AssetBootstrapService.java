package cn.smartbox.asset;

import java.time.Clock;
import java.util.*;
import cn.smartbox.auth.*;
import cn.smartbox.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit metadata initialization, separate from account/password bootstrap. */
@Service
public class AssetBootstrapService {
    private final JdbcTemplate db;private final AuthMapper users;private final Clock clock;
    public AssetBootstrapService(JdbcTemplate db,AuthMapper users,Clock clock){this.db=db;this.users=users;this.clock=clock;}
    private String string(JsonNode row,String key,int max){var value=row.path(key);if(!value.isTextual()||!Inputs.length(value.asText(),1,max))throw ApiException.invalid();return value.asText();}
    private UserAccount account(String username,String role){var user=users.byUsername(username);if(user==null||!Boolean.TRUE.equals(user.enabled())||!role.equals(user.roleCode()))throw ApiException.invalid();return user;}
    @Transactional public void initialize(JsonNode input){
        if(!input.isObject()||!input.path("enterprises").isArray()||!input.path("personal_boxes").isArray()||!input.path("rider_usernames").isArray())throw ApiException.invalid();
        Set<String> riders=new HashSet<>(),admins=new HashSet<>(),boxes=new HashSet<>();long now=clock.millis();
        for(JsonNode name:input.path("rider_usernames")){
            if(!name.isTextual()||!riders.add(name.asText()))throw ApiException.invalid();account(name.asText(),"COURIER");
        }
        for(JsonNode company:input.path("enterprises")){
            String name=string(company,"name",128),adminName=string(company,"admin_username",128);
            if(!admins.add(adminName)||!company.path("box_ids").isArray())throw ApiException.invalid();
            var admin=account(adminName,"LOGISTICS_OPERATOR");
            var prior=db.queryForList("SELECT id,name FROM enterprises WHERE admin_user_id=? FOR UPDATE",admin.id());
            long id;
            if(prior.isEmpty()){
                db.update("INSERT INTO enterprises(name,admin_user_id,created_at) VALUES(?,?,?)",name,admin.id(),now);
                id=db.queryForObject("SELECT id FROM enterprises WHERE admin_user_id=?",Long.class,admin.id());
            }else{if(!name.equals(prior.getFirst().get("name")))throw ApiException.conflict();id=((Number)prior.getFirst().get("id")).longValue();}
            for(JsonNode box:company.path("box_ids")){
                if(!box.isTextual()||!Inputs.id(box.asText())||!boxes.add(box.asText())||users.boxExists(box.asText())!=1)throw ApiException.invalid();
                ownership(box.asText(),"ENTERPRISE",id,null,now);
            }
        }
        for(JsonNode box:input.path("personal_boxes")){
            String id=string(box,"box_id",48),name=string(box,"display_name",128),ownerName=string(box,"owner_username",128);
            if(!Inputs.id(id)||!boxes.add(id)||!riders.contains(ownerName))throw ApiException.invalid();var owner=account(ownerName,"COURIER");
            // An offline demo asset is not a sensor node and has no fabricated data.
            if(users.boxExists(id)==0)db.update("INSERT INTO boxes(box_id,display_name,created_at,updated_at) VALUES(?,?,?,?)",id,name,now,now);
            ownership(id,"PERSONAL",null,owner.id(),now);
        }
    }
    private void ownership(String box,String type,Long enterprise,Long person,long now){
        var rows=db.queryForList("SELECT owner_type,enterprise_id,owner_user_id FROM box_ownerships WHERE box_id=? FOR UPDATE",box);
        if(rows.isEmpty())db.update("INSERT INTO box_ownerships(box_id,owner_type,enterprise_id,owner_user_id,created_at) VALUES(?,?,?,?,?)",box,type,enterprise,person,now);
        else{
            var row=rows.getFirst();Object e=row.get("enterprise_id"),p=row.get("owner_user_id");
            if(!type.equals(row.get("owner_type"))||!Objects.equals(enterprise,e==null?null:((Number)e).longValue())||!Objects.equals(person,p==null?null:((Number)p).longValue()))throw ApiException.conflict();
        }
    }
}
