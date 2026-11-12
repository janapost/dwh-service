package kz.janapost.dwhservice.config;

import kz.janapost.dwhservice.config.interceptor.AdminAuthInterceptor;
import kz.janapost.dwhservice.config.interceptor.CORSInterceptor;
import kz.janapost.dwhservice.config.interceptor.ClientAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final CORSInterceptor corsInterceptor;
    private final ClientAuthInterceptor clientAuthInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(corsInterceptor)
                .addPathPatterns("/**");
        registry.addInterceptor(clientAuthInterceptor)
                .addPathPatterns("/client/**");
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns(
                        "/admin/**",
                        "/call_centre/**"
                );
    }
}
