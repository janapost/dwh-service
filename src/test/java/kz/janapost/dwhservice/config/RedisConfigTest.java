package kz.janapost.dwhservice.config;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Кастомайзер {@link RedisConfig} в настоящей автоконфигурации Spring Boot: если порядок применения
 * кастомайзеров и {@code clientOptions} изменится, тест это заметит. Соединение с Redis не открывается.
 */
class RedisConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withUserConfiguration(RedisConfig.class)
            .withPropertyValues(
                    "spring.data.redis.host=localhost",
                    "spring.data.redis.timeout=300ms",
                    "spring.data.redis.connect-timeout=1s");

    @Test
    @DisplayName("REJECT_COMMANDS включён, connect-timeout и timeout из свойств сохранены")
    void rejectCommandsKeepsBootOptions() {
        contextRunner.run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            ClientOptions options = factory.getClientConfiguration().getClientOptions().orElseThrow();

            assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
            assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(300));
        });
    }
}
