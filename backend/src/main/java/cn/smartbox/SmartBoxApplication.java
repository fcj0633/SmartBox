package cn.smartbox;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class SmartBoxApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SmartBoxApplication.class);
        boolean bootstrap = java.util.Arrays.asList(args).contains("--smartbox.bootstrap=true")||java.util.Arrays.asList(args).contains("--smartbox.integration.reset=true");
        if (bootstrap) app.setWebApplicationType(WebApplicationType.NONE);
        try {
            var context = app.run(args);
            if (bootstrap) context.close();
        } catch (Exception failure) {
            // Do not echo configuration, initialization input or SQL on startup failure.
            System.err.println("SmartBox startup failed; verify local configuration and database availability.");
            System.exit(1);
        }
    }
}
