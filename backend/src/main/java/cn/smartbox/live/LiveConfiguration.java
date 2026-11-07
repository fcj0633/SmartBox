package cn.smartbox.live;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Bean;
@Configuration
@EnableScheduling
public class LiveConfiguration {
    @Bean public org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer strictJson(){return builder->builder.featuresToEnable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
    @Bean public ThreadPoolTaskScheduler taskScheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(5);scheduler.setThreadNamePrefix("smartbox-work-");scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(8);return scheduler;}
}
