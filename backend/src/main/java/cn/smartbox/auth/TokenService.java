package cn.smartbox.auth;
import java.time.*;
import java.util.List;
import cn.smartbox.config.LocalSettings;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
@Service
public class TokenService {
    private final Clock clock;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    public TokenService(LocalSettings settings,Clock clock) {
        this.clock=clock;
        encoder=new NimbusJwtEncoder(new ImmutableSecret<>(settings.key()));
        decoder=NimbusJwtDecoder.withSecretKey(settings.key()).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(jwt->valid(jwt,clock.instant())?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Invalid or expired token",null)));
    }
    public static boolean valid(Jwt jwt,Instant now) {
        try {
            Instant issued=jwt.getIssuedAt(),expires=jwt.getExpiresAt();String sub=jwt.getSubject();
            return sub!=null&&sub.matches("[1-9][0-9]{0,18}")&&Long.parseLong(sub)>0
                &&jwt.getClaimAsString("iss").equals("smartbox")&&jwt.getAudience().contains("smartbox-web")
                &&issued!=null&&expires!=null&&!issued.isAfter(now)&&expires.equals(issued.plusSeconds(1800))
                &&now.isBefore(expires)&&(jwt.getNotBefore()==null||!now.isBefore(jwt.getNotBefore()));
        } catch(RuntimeException e){return false;}
    }
    public record Issued(String value,long expiresAt){}
    public Issued issue(long userId){
        Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        JwtClaimsSet claims=JwtClaimsSet.builder().issuer("smartbox").audience(List.of("smartbox-web")).subject(Long.toString(userId)).issuedAt(now).expiresAt(now.plusSeconds(1800)).build();
        Jwt token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims));
        return new Issued(token.getTokenValue(),token.getExpiresAt().toEpochMilli());
    }
    public JwtDecoder decoder(){return decoder;}
}
