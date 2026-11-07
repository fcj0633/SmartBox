package cn.smartbox.live;
import cn.smartbox.config.LocalSettings;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.context.annotation.Bean;
@Configuration
@EnableWebSocket
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final LiveWebSocket handler;private final LocalSettings settings;
    public WebSocketConfiguration(LiveWebSocket handler,LocalSettings settings){this.handler=handler;this.settings=settings;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(handler,"/ws").addInterceptors(handler).setAllowedOrigins(settings.origins().toArray(String[]::new));}
    @Bean public ServletServerContainerFactoryBean webSocketContainer(){var container=new ServletServerContainerFactoryBean();container.setMaxTextMessageBufferSize(8192);container.setAsyncSendTimeout(2000L);return container;}
}
