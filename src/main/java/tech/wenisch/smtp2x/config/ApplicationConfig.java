package tech.wenisch.smtp2x.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(Smtp2xProperties.class)
public class ApplicationConfig implements WebMvcConfigurer {
  @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
  @Override public void extendMessageConverters(java.util.List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
    converters.add(0, new MappingJackson2HttpMessageConverter(objectMapper()));
  }
}
