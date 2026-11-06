package cn.smartbox;
import cn.smartbox.auth.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.dao.DataAccessResourceFailureException;
import java.net.URI;
import java.net.http.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BackendFailureIT {
    @MockitoBean AuthMapper users;
    @Autowired TokenService tokens;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @Test void databaseFailureProduces503WithoutStoppingSharedMysql()throws Exception{
        when(users.byUsername(anyString())).thenThrow(new DataAccessResourceFailureException("private database diagnostic"));
        var client=HttpClient.newHttpClient();
        var login=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"failure_probe\",\"password\":\"temporary_test_input\"}")).build();
        var response=client.send(login,HttpResponse.BodyHandlers.ofString());assertEquals(503,response.statusCode());assertFalse(response.body().contains("private database diagnostic"));
        when(users.byId(anyLong())).thenThrow(new DataAccessResourceFailureException("private database diagnostic"));
        var me=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/me")).header("Authorization","Bearer "+tokens.issue(1).value()).GET().build();
        response=client.send(me,HttpResponse.BodyHandlers.ofString());assertEquals(503,response.statusCode());assertFalse(response.body().contains("private database diagnostic"));
    }
}
