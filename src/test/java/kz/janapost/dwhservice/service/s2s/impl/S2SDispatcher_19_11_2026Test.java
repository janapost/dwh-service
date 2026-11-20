package kz.janapost.dwhservice.service.s2s.impl;

import kz.janapost.dwhservice.config.properties.S2SProperties;
import kz.janapost.dwhservice.exception.S2SDeliveryException;
import kz.janapost.dwhservice.model.s2s.S2SEvent;
import kz.janapost.dwhservice.model.s2s.S2SEventType;
import kz.janapost.dwhservice.model.s2s.S2SIdentity;
import kz.janapost.dwhservice.service.s2s.S2SService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S2SDispatcher_19_11_2026Test {

    private static final S2SEvent EVENT = new S2SEvent(S2SEventType.ORDER_CREATED, "JP1", 1L, "O-1",
            null, null, 100L, null, null, null);

    private final RecordingProvider appsflyer = new RecordingProvider("appsflyer_s2s_sender");
    private final RecordingProvider adjust = new RecordingProvider("adjust_s2s_sender");
    private final RecordingProvider unused = new RecordingProvider("unused_s2s_sender");

    @Test
    @DisplayName("событие получают основной и параллельные провайдеры, неактивный — нет")
    void sendsToDefaultAndParallel() {
        S2SDispatcher_19_11_2026 dispatcher = dispatcher("appsflyer_s2s_sender", " adjust_s2s_sender ");

        dispatcher.send(EVENT);

        assertThat(appsflyer.sent).containsExactly(EVENT);
        assertThat(adjust.sent).containsExactly(EVENT);
        assertThat(unused.sent).isEmpty();
        assertThat(dispatcher.activeServices()).extracting(S2SService::beanName)
                .containsExactly("appsflyer_s2s_sender", "adjust_s2s_sender");
    }

    @Test
    @DisplayName("ошибка одного провайдера не мешает другому; после всех — исключение для повтора события")
    void oneProviderFailureDoesNotStopOthers() {
        appsflyer.failure = new IllegalStateException("mongo down");
        S2SDispatcher_19_11_2026 dispatcher = dispatcher("appsflyer_s2s_sender", "adjust_s2s_sender");

        assertThatThrownBy(() -> dispatcher.send(EVENT))
                .isInstanceOf(S2SDeliveryException.class)
                .hasMessageContaining("appsflyer_s2s_sender")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(adjust.sent).containsExactly(EVENT);
    }

    @Test
    @DisplayName("ID устройства получают все зарегистрированные провайдеры, даже неактивные")
    void identityGoesToAllProviders() {
        S2SIdentity identity = new S2SIdentity("JP1", "1712345678901-1", "ios", null, null);

        dispatcher("appsflyer_s2s_sender").saveIdentity(identity);

        assertThat(appsflyer.identities).containsExactly(identity);
        assertThat(adjust.identities).containsExactly(identity);
        assertThat(unused.identities).containsExactly(identity);
    }

    @Test
    @DisplayName("неизвестное имя провайдера в default-bean или parallel-beans — сервис не стартует")
    void unknownBeanFailsStartup() {
        assertThatThrownBy(() -> dispatcher("adjsut_s2s_sender").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("adjsut_s2s_sender");
        assertThatThrownBy(() -> dispatcher("appsflyer_s2s_sender", "typo").validate())
                .isInstanceOf(IllegalStateException.class);
    }

    private S2SDispatcher_19_11_2026 dispatcher(String defaultBean, String... parallel) {
        Map<String, S2SService> services = new LinkedHashMap<>();
        for (RecordingProvider provider : List.of(appsflyer, adjust, unused)) {
            services.put(provider.beanName(), provider);
        }
        S2SProperties properties = new S2SProperties();
        properties.setDefaultBean(defaultBean);
        properties.setParallelBeans(List.of(parallel));
        return new S2SDispatcher_19_11_2026(services, properties);
    }

    /** Провайдер-заглушка: записывает вызовы, может упасть. */
    private static final class RecordingProvider implements S2SService {
        private final String name;
        private final List<S2SEvent> sent = new ArrayList<>();
        private final List<S2SIdentity> identities = new ArrayList<>();
        private RuntimeException failure;

        private RecordingProvider(String name) {
            this.name = name;
        }

        @Override
        public String beanName() {
            return name;
        }

        @Override
        public void saveIdentity(S2SIdentity identity) {
            identities.add(identity);
        }

        @Override
        public void send(S2SEvent event) {
            if (failure != null) {
                throw failure;
            }
            sent.add(event);
        }

        @Override
        public void retryPending() {
            // не нужен в этих тестах
        }

        @Override
        public void cleanup() {
            // не нужен в этих тестах
        }
    }
}
