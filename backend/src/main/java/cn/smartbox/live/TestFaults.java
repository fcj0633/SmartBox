package cn.smartbox.live;
import java.nio.file.*;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** No HTTP control surface. Explicit test-process-only barriers for crash-window acceptance. */
@Component
public class TestFaults {
    private final Environment env;
    public TestFaults(Environment env){this.env=env;}
    public boolean active(String key){
        if(!Arrays.asList(env.getActiveProfiles()).contains("test")||!env.getProperty("SMARTBOX_TEST_FAULTS",Boolean.class,false))return false;
        String marker=env.getProperty(key);return marker!=null&&Files.isRegularFile(Path.of(marker));
    }
}
