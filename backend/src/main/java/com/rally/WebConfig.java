package com.rally;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.context.annotation.Bean;
import java.nio.file.Path;

@Configuration
@EnableWebSocket
public class WebConfig implements WebSocketConfigurer,WebMvcConfigurer {
    final GameSocket socket;
    final String[] origins;
    final Path images;
    public WebConfig(GameSocket socket,@Value("${app.origins}") String origins,@Value("${app.images}") String images) { this.socket=socket;this.origins=origins.split(",");this.images=Path.of(images).toAbsolutePath(); }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) { registry.addHandler(socket,"/ws/game/*").setAllowedOrigins(origins); }
    @Override public void addCorsMappings(CorsRegistry registry) { registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET","POST","PUT","DELETE").allowedHeaders("Authorization","Content-Type"); }
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) { registry.addResourceHandler("/images/**").addResourceLocations(images.toUri().toString()+"/"); }
    @Bean public ServletServerContainerFactoryBean websocketContainer() {
        var container=new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8192);container.setMaxSessionIdleTimeout(60000L);
        return container;
    }
}
