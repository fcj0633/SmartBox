package cn.smartbox.config;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="smartbox.bootstrap",havingValue="true")
public class BootstrapRunner implements ApplicationRunner {
    private final BootstrapService service;private final Environment env;private final ObjectMapper json;
    public BootstrapRunner(BootstrapService service,Environment env,ObjectMapper json){this.service=service;this.env=env;this.json=json;}
    @Override public void run(ApplicationArguments args)throws Exception {
        Path path=Path.of(env.getRequiredProperty("SMARTBOX_BOOTSTRAP_FILE"));
        if(!Files.isRegularFile(path)||Files.size(path)>1024*1024)throw new IllegalArgumentException("Invalid local initialization file");
        service.initialize(json.readTree(Files.readString(path)));System.out.println("SmartBox controlled initialization completed; no credentials displayed.");
    }
}
