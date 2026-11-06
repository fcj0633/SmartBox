package cn.smartbox.config;
import cn.smartbox.auth.*;
import cn.smartbox.audit.AuditService;
import cn.smartbox.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean public SecurityFilterChain security(HttpSecurity http,TokenService tokens,AuthMapper users,AuditService audit,ObjectMapper json)throws Exception {
        http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(r->r.disable()).formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable()).cors(c->{})
            .authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/login","/actuator/health").permitAll().anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,error)->{
                audit.record(null,null,null,"ACCESS_DENIED","UNAUTHORIZED");
                RequestContextFilter.write(res,json,401,"UNAUTHORIZED","身份无效或已过期");
            }).accessDeniedHandler((req,res,error)->RequestContextFilter.write(res,json,403,"FORBIDDEN","无权访问")))
            .oauth2ResourceServer(o->o.jwt(j->j.decoder(tokens.decoder()).jwtAuthenticationConverter(jwt->{
                UserAccount account=users.byId(Long.parseLong(jwt.getSubject()));
                if(account==null||!Boolean.TRUE.equals(account.enabled()))throw new BadCredentialsException("Invalid identity");
                CurrentUser current=new CurrentUser(account.id(),account.username(),account.roleCode());
                return UsernamePasswordAuthenticationToken.authenticated(current,null,List.of(new SimpleGrantedAuthority("ROLE_"+account.roleCode())));
            })).authenticationEntryPoint((req,res,error)->{
                audit.record(null,null,null,"ACCESS_DENIED","UNAUTHORIZED");
                RequestContextFilter.write(res,json,401,"UNAUTHORIZED","身份无效或已过期");
            }));
        return http.build();
    }
    @Bean(name="corsConfigurationSource") public CorsConfigurationSource cors(LocalSettings settings){
        CorsConfiguration c=new CorsConfiguration();c.setAllowedOrigins(settings.origins());c.setAllowedMethods(List.of("GET","POST","OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization","Content-Type","Idempotency-Key"));c.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",c);return source;
    }
}
