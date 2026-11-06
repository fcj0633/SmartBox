package cn.smartbox.config;
import java.net.URI;
import java.util.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
@Component
public final class LocalSettings {
    private final SecretKey key;
    private final List<String> origins;
    public LocalSettings(Environment env) {
        Set<String> profiles=Set.of(env.getActiveProfiles());
        if(profiles.contains("dev")==profiles.contains("test"))throw new IllegalArgumentException("Select exactly one dev/test profile");
        String db=profiles.contains("test")?"smartbox_test":"smartbox_dev";
        String url=env.getRequiredProperty("SMARTBOX_DB_URL");
        String migration=env.getRequiredProperty("SMARTBOX_MIGRATION_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3306/"+db+"(?:\\?.*)?")||!url.equals(migration))throw new IllegalArgumentException("Project database mismatch");
        String app=profiles.contains("test")?"smartbox_test_app":"smartbox_app";
        String migrator=profiles.contains("test")?"smartbox_test_migrator":"smartbox_migrator";
        if(!app.equals(env.getRequiredProperty("SMARTBOX_DB_USER"))||!migrator.equals(env.getRequiredProperty("SMARTBOX_MIGRATION_USER")))throw new IllegalArgumentException("Project database account mismatch");
        if(env.getRequiredProperty("SMARTBOX_DB_PASSWORD").isEmpty()||env.getRequiredProperty("SMARTBOX_MIGRATION_PASSWORD").isEmpty())throw new IllegalArgumentException("Missing database credential");
        byte[] decoded;
        try{decoded=Base64.getDecoder().decode(env.getRequiredProperty("SMARTBOX_JWT_SECRET_BASE64"));}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid JWT key configuration");}
        if(decoded.length<32)throw new IllegalArgumentException("JWT key is too short");
        key=new SecretKeySpec(decoded,"HmacSHA256");
        origins=Arrays.stream(env.getProperty("SMARTBOX_ALLOWED_ORIGINS","http://localhost:5173,http://127.0.0.1:5173").split(",")).map(String::trim).toList();
        for(String origin:origins) {
            URI uri=URI.create(origin);
            if(uri.getScheme()==null||!List.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getRawPath().isEmpty())throw new IllegalArgumentException("Invalid Origin allowlist");
        }
    }
    public SecretKey key(){return key;}
    public List<String> origins(){return origins;}
}
