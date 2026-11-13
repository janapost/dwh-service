package kz.janapost.dwhservice.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis нужен только лимитам частоты, и они работают и без него (fail-open). Поэтому при разорванном соединении
 * команда сразу отклоняется, а не встаёт в очередь до переподключения: иначе каждый запрос ждал бы
 * {@code spring.data.redis.timeout} на потоке Tomcat.
 * <p>
 * Кастомайзер выполняется после того, как Spring Boot выставил {@code clientOptions} (в том числе
 * {@code connect-timeout}), поэтому берём готовые опции и меняем только поведение при разрыве.
 */
@Configuration
public class RedisConfig {

    @Bean
    public LettuceClientConfigurationBuilderCustomizer rejectCommandsWhenDisconnected() {
        return builder -> {
            ClientOptions current = builder.build().getClientOptions().orElseGet(ClientOptions::create);
            builder.clientOptions(current.mutate()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build());
        };
    }
}
