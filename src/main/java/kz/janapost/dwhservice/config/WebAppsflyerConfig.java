package kz.janapost.dwhservice.config;

import io.netty.channel.ChannelOption;
import kz.janapost.dwhservice.config.properties.AppsflyerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * HTTP-клиент AppsFlyer S2S: заголовки — как в client-service ({@code WebAppsFlyerConfig}), плюс таймауты
 * соединения и ответа. Без них зависший AppsFlyer держал бы поток консьюмера {@code event_s2s} без ограничения.
 */
@Configuration
public class WebAppsflyerConfig {
    public static final String APPSFLYER_WEB_CLIENT = "appsflyerWebClient";
    private static final String AUTHENTICATION_HEADER = "authentication";

    @Bean(APPSFLYER_WEB_CLIENT)
    public WebClient appsflyerWebClient(AppsflyerProperties props) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(props.getConnectTimeout().toMillis()))
                .responseTimeout(props.getReadTimeout());
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(AUTHENTICATION_HEADER, props.getS2sKey() == null ? "" : props.getS2sKey())
                .build();
    }
}
