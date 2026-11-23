package cn.smartbox.asset;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import cn.smartbox.common.Inputs;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Optional, server-controlled demo rider allowlist. Missing config grants nobody. */
@Component
public class DemoAssetConfig {
    private final JsonNode seed;
    private final Set<String> riders;
    public DemoAssetConfig(Environment env,ObjectMapper json) {
        String file=env.getProperty("SMARTBOX_ASSET_SEED_FILE");
        if(file==null||file.isBlank()){seed=null;riders=Set.of();return;}
        try {
            Path path=Path.of(file);
            if(!Files.isRegularFile(path)||Files.isSymbolicLink(path)||Files.size(path)>1048576)throw new IllegalArgumentException();
            seed=json.readTree(Files.readString(path));
            if(!seed.isObject()||!seed.path("rider_usernames").isArray())throw new IllegalArgumentException();
            Set<String> names=new HashSet<>();
            for(JsonNode name:seed.path("rider_usernames"))
                if(!name.isTextual()||!Inputs.length(name.asText(),1,128)||!names.add(name.asText()))throw new IllegalArgumentException();
            riders=Set.copyOf(names);
        }catch(Exception invalid){throw new IllegalArgumentException("Invalid local asset configuration");}
    }
    public boolean allows(String username){return riders.contains(username);}
    public JsonNode seed(){if(seed==null)throw new IllegalArgumentException("Explicit asset seed configuration required");return seed.deepCopy();}
}
