package cn.smartbox;
import cn.smartbox.live.*;
import cn.smartbox.common.ApiException;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class LiveUnitTest {
    final ContractValidator validator=new ContractValidator();
    LiveUnitTest()throws Exception{}
    @Test void parserRejectsDuplicateTrailingMalformed(){
        for(String text:List.of("{\"a\":1,\"a\":2}","{\"a\":NaN}","{}{}","[]"))assertThrows(ApiException.class,()->validator.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(ApiException.class,()->validator.parse(new byte[]{'{','"',(byte)0xff,'"',':','1','}'}));
    }
    @Test void canonicalNumericAndKeyOrder(){assertArrayEquals(validator.hash(validator.json("{\"a\":1,\"b\":2.00}")),validator.hash(validator.json("{\"b\":2,\"a\":1.0}")));}
    @Test void immutableOutcomeInputs(){
        assertEquals("SUCCEEDED",CommandService.outcome(List.of("SUCCEEDED","SUCCEEDED"),false));
        assertEquals("RUNNING",CommandService.outcome(List.of("SUCCEEDED","PENDING"),false));
        assertEquals("PARTIAL",CommandService.outcome(List.of("SUCCEEDED","PENDING"),true));
        assertEquals("EXPIRED",CommandService.outcome(List.of("FAILED","PENDING"),true));
        assertEquals("FAILED",CommandService.outcome(List.of("FAILED","FAILED"),false));
    }
    @Test void freshnessBoundaries(){
        assertEquals("FRESH",QueryService.freshness(true,BigInteger.valueOf(14999),15000));
        assertEquals("STALE",QueryService.freshness(true,BigInteger.valueOf(15000),15000));
        assertEquals("STALE",QueryService.freshness(true,BigInteger.valueOf(-1),5000));
        assertEquals("UNKNOWN",QueryService.freshness(false,BigInteger.ZERO,15000));
    }
    @Test void csvInjectionAndEscapes(){assertEquals("\"'=cmd\"",QueryService.csvCell("=cmd"));assertEquals("\"a\"\"b,c\"",QueryService.csvCell("a\"b,c"));assertTrue(QueryService.csvCell("\t=cmd\nline").startsWith("\"'"));}
    @Test void alarmUnknownCannotClaimNormal(){
        validator.validate("Alarm",validator.json("{\"assessment\":\"UNKNOWN\",\"active\":null,\"level\":null}"));
        assertThrows(ApiException.class,()->validator.validate("Alarm",validator.json("{\"assessment\":\"UNKNOWN\",\"active\":false,\"level\":null}")));
    }
    @Test void publicEventProjectionDropsUnknownCredentialFields(){
        var data=validator.json("{\"component\":\"simulation\",\"code\":\"TEST_INPUT\",\"password\":\"forbidden\"}");
        assertFalse(validator.eventData("DEVICE_FAULT",data).has("password"));
        assertFalse(validator.actualState(validator.json("{\"on\":true,\"device_secret\":\"forbidden\"}")).has("device_secret"));
    }
    @Test void sqlBindingsCannotTurnParameterIntoSql(){
        String value="' OR 1=1 --";
        String statement=LiveMapper.Sql.bound(Map.of("sql","SELECT node_id FROM nodes WHERE node_id=? AND '?'='?'","args",new Object[]{value}));
        assertFalse(statement.contains(value));assertTrue(statement.contains("#{args[0]}"));
    }
}
