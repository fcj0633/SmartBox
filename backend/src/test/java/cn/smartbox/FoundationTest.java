package cn.smartbox;
import cn.smartbox.auth.*;
import cn.smartbox.authorization.*;
import cn.smartbox.audit.AuditService;
import cn.smartbox.common.*;
import cn.smartbox.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FoundationTest {
    static MockEnvironment environment(){
        byte[] key=new byte[64];new java.security.SecureRandom().nextBytes(key);
        MockEnvironment e=new MockEnvironment();e.setActiveProfiles("dev");
        return e.withProperty("SMARTBOX_DB_URL","jdbc:mysql://127.0.0.1:3306/smartbox_dev").withProperty("SMARTBOX_MIGRATION_URL","jdbc:mysql://127.0.0.1:3306/smartbox_dev")
            .withProperty("SMARTBOX_DB_USER","smartbox_app").withProperty("SMARTBOX_MIGRATION_USER","smartbox_migrator")
            .withProperty("SMARTBOX_DB_PASSWORD",UUID.randomUUID().toString()).withProperty("SMARTBOX_MIGRATION_PASSWORD",UUID.randomUUID().toString())
            .withProperty("SMARTBOX_JWT_SECRET_BASE64",Base64.getEncoder().encodeToString(key));
    }
    @ParameterizedTest @ValueSource(strings={"LOGISTICS_VIEWER","LOGISTICS_OPERATOR","DELIVERY_USER","COURIER"})
    void permissionMatrix(String role){
        PermissionPolicy p=new PermissionPolicy();CurrentUser u=new CurrentUser(1,role,role);
        assertTrue(p.allowed(u,PermissionPolicy.Action.READ_BUSINESS));
        assertEquals(!role.equals("DELIVERY_USER"),p.allowed(u,PermissionPolicy.Action.READ_DIAGNOSTICS));
        assertEquals(Set.of("LOGISTICS_OPERATOR","COURIER").contains(role),p.allowed(u,PermissionPolicy.Action.SENTRY_CONTROL));
        assertEquals(role.equals("LOGISTICS_OPERATOR"),p.allowed(u,PermissionPolicy.Action.CONFIGURE));
        assertEquals(role.equals("LOGISTICS_OPERATOR"),p.allowed(u,PermissionPolicy.Action.EXPORT));
        assertFalse(p.allowed(new CurrentUser(1,"unknown","ROOT"),PermissionPolicy.Action.READ_BUSINESS));
    }
    @Test void unicodeBoundaries(){
        assertTrue(Inputs.length("\uD83D\uDE00".repeat(256),1,256));assertFalse(Inputs.length("\uD83D\uDE00".repeat(257),1,256));
        assertFalse(Inputs.length("\uD800",1,256));assertFalse(Inputs.length(null,1,256));
        assertTrue(Inputs.id("BOX-0001_ENV"));assertFalse(Inputs.id("box/#"));assertFalse(Inputs.id("a".repeat(49)));
    }
    @Test void passwordHashIsSaltedAndPreservesLongUnicode(){
        var p=new CoreConfig().passwordEncoder();String password="\uD83D\uDE00".repeat(256);String one=p.encode(password),two=p.encode(password);
        assertNotEquals(one,two);assertTrue(one.startsWith("{pbkdf2@SpringSecurity_v5_8}"));
        assertTrue(p.matches(password,one));assertFalse(p.matches(password.substring(2),one));assertFalse(one.contains(password));
    }
    @Test void uint64TypeHandler()throws Exception{
        var handler=new Unsigned64TypeHandler();var statement=mock(PreparedStatement.class);var result=mock(ResultSet.class);
        handler.setNonNullParameter(statement,1,Unsigned64TypeHandler.MAX,null);verify(statement).setBigDecimal(1,new BigDecimal(Unsigned64TypeHandler.MAX));
        when(result.getBigDecimal(1)).thenReturn(new BigDecimal(Unsigned64TypeHandler.MAX));assertEquals(Unsigned64TypeHandler.MAX,handler.getNullableResult(result,1));
        when(result.getBigDecimal(1)).thenReturn(null);assertNull(handler.getNullableResult(result,1));
        when(result.getBigDecimal(1)).thenReturn(new BigDecimal("1.2"));assertThrows(SQLException.class,()->handler.getNullableResult(result,1));
        assertThrows(IllegalArgumentException.class,()->Unsigned64TypeHandler.checked(BigInteger.valueOf(-1)));
        assertThrows(IllegalArgumentException.class,()->Unsigned64TypeHandler.checked(Unsigned64TypeHandler.MAX.add(BigInteger.ONE)));
    }
    @Test void invalidLocalSettingsFailBeforeDatabase(){
        var e=environment();assertDoesNotThrow(()->new LocalSettings(e));
        e.setProperty("SMARTBOX_MIGRATION_URL","jdbc:mysql://127.0.0.1:3306/another_project");assertThrows(IllegalArgumentException.class,()->new LocalSettings(e));
        var shortKey=environment().withProperty("SMARTBOX_JWT_SECRET_BASE64",Base64.getEncoder().encodeToString(new byte[8]));assertThrows(IllegalArgumentException.class,()->new LocalSettings(shortKey));
        var wildcard=environment().withProperty("SMARTBOX_ALLOWED_ORIGINS","*");assertThrows(IllegalArgumentException.class,()->new LocalSettings(wildcard));
    }
    @Test void tokenSignatureExpiryAndAlgorithm(){
        var settings=new LocalSettings(environment());Instant now=Instant.parse("2026-11-06T00:00:00Z");var tokens=new TokenService(settings,Clock.fixed(now,ZoneOffset.UTC));
        var issued=tokens.issue(1);Jwt jwt=tokens.decoder().decode(issued.value());
        assertEquals(now.plusSeconds(1800).toEpochMilli(),issued.expiresAt());assertTrue(TokenService.valid(jwt,now.plusSeconds(1799)));
        assertFalse(TokenService.valid(jwt,now.plusSeconds(1800)));assertFalse(TokenService.valid(jwt,now.minusSeconds(1)));
        assertThrows(JwtException.class,()->tokens.decoder().decode(issued.value().substring(0,issued.value().length()-8)+"invalid!"));
        var encoder=new NimbusJwtEncoder(new ImmutableSecret<>(settings.key()));
        var claims=JwtClaimsSet.builder().issuer("smartbox").subject("1").audience(List.of("smartbox-web")).issuedAt(now).expiresAt(now.plusSeconds(1800)).build();
        String other=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS384).build(),claims)).getTokenValue();
        assertThrows(JwtException.class,()->tokens.decoder().decode(other));
    }
    @ParameterizedTest @ValueSource(strings={"iss","aud","sub","iat","exp"})
    void missingRequiredClaimRejected(String field){
        Instant now=Instant.parse("2026-11-06T00:00:00Z");var builder=Jwt.withTokenValue("unit-fixture").header("alg","HS256");
        Map<String,Object> claims=new HashMap<>();claims.put("iss","smartbox");claims.put("aud",List.of("smartbox-web"));claims.put("sub","1");claims.put("iat",now);claims.put("exp",now.plusSeconds(1800));claims.remove(field);
        builder.claims(c->c.putAll(claims));assertFalse(TokenService.valid(builder.build(),now));
    }
    @Test void testCleanupGuardRefusesOtherDatabases(){
        String url="jdbc:mysql://127.0.0.1:3306/smartbox_test";
        assertDoesNotThrow(()->TestDatabaseGuard.require("test",url,"smartbox_test","smartbox_test_app@localhost","true"));
        assertThrows(IllegalStateException.class,()->TestDatabaseGuard.require("dev",url,"smartbox_test","smartbox_test_app@localhost","true"));
        assertThrows(IllegalStateException.class,()->TestDatabaseGuard.require("test",url,"smartbox_dev","smartbox_test_app@localhost","true"));
        assertThrows(IllegalStateException.class,()->TestDatabaseGuard.require("test",url,"smartbox_test","root@localhost","true"));
        assertThrows(IllegalStateException.class,()->TestDatabaseGuard.require("test",url,"smartbox_test","smartbox_test_app@localhost","false"));
    }
    @Test void deploymentCanUseAnExplicitLocalDatabasePort(){
        var e=environment();
        e.setProperty("SMARTBOX_DB_URL","jdbc:mysql://127.0.0.1:13306/smartbox_dev");
        e.setProperty("SMARTBOX_MIGRATION_URL","jdbc:mysql://127.0.0.1:13306/smartbox_dev");
        assertDoesNotThrow(()->new LocalSettings(e));
        assertDoesNotThrow(()->TestDatabaseGuard.require("test","jdbc:mysql://127.0.0.1:13306/smartbox_test","smartbox_test","smartbox_test_app@localhost","true"));
        for(String invalid:List.of("jdbc:mysql://192.168.1.1:13306/smartbox_dev","jdbc:mysql://127.0.0.1:65536/smartbox_dev","jdbc:mysql://127.0.0.1:0/smartbox_dev")){
            e.setProperty("SMARTBOX_DB_URL",invalid);e.setProperty("SMARTBOX_MIGRATION_URL",invalid);
            assertThrows(IllegalArgumentException.class,()->new LocalSettings(e));
        }
    }
    @Test void authorizationBeforeExistence(){
        var mapper=mock(AuthMapper.class);var audit=mock(AuditService.class);var access=new BoxAccessService(mapper,new PermissionPolicy(),audit);var user=new CurrentUser(1,"u","LOGISTICS_VIEWER");
        assertEquals(403,assertThrows(ApiException.class,()->access.require(user,"BOX-0001",PermissionPolicy.Action.READ_DIAGNOSTICS)).status());verify(mapper,never()).boxExists(anyString());
        when(mapper.hasBox(1,"BOX-0001")).thenReturn(1);
        assertEquals(404,assertThrows(ApiException.class,()->access.require(user,"BOX-0001",PermissionPolicy.Action.READ_DIAGNOSTICS)).status());
    }
    @Test void deliveryProjectionUsesNestedWhitelist()throws Exception{
        var json=new ObjectMapper();var source=json.readTree("""
          {"box_id":"BOX-0001","online_mask":0,"connectivity":"UNKNOWN","pose":{"secret":1},"evidence":[1],"sentry":{"state":"ARMED"},
           "alarm":{"active":false,"level":null,"evidence":"secret"},"door":{"state":"UNKNOWN","confidence":"INSUFFICIENT","conclusion_id":null,"observed_at":null,"historical":false,"raw_events":[1]},
           "env":{"value":{"temp_c":22,"humi_pct":50,"lux":100,"valid":true,"light_on":false,"fan_on":false,"pose":"secret"},"valid":true,"observed_at":null,"received_at":1,"stale":false,"src":"MOCK","time_quality":"UNSYNCED"},
           "loc":{"value":{"lat":31,"lon":121,"valid":true,"src":"MOCK","evidence":1},"valid":true,"observed_at":null,"received_at":1,"stale":false,"src":"MOCK","time_quality":"UNSYNCED"}}
          """);
        var result=json.valueToTree(new RoleProjectionService(new PermissionPolicy()).delivery(new CurrentUser(1,"u","DELIVERY_USER"),source));
        for(String forbidden:List.of("pose","evidence","raw_events","sentry","secret"))assertFalse(result.toString().contains("\""+forbidden+"\""));
        assertEquals("MOCK",result.path("loc").path("value").path("src").asText());assertTrue(result.path("door").has("historical"));
    }
}
