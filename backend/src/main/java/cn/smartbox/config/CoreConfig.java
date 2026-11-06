package cn.smartbox.config;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.*;
@Configuration
public class CoreConfig {
    @Bean public static org.springframework.beans.factory.config.BeanFactoryPostProcessor validateBeforeDatabase(org.springframework.core.env.Environment env){
        new LocalSettings(env);return factory->{};
    }
    @Bean public Clock clock(){return Clock.systemUTC();}
    @Bean public PasswordEncoder passwordEncoder(){
        String id="pbkdf2@SpringSecurity_v5_8";
        return new DelegatingPasswordEncoder(id,Map.of(id,Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
}
