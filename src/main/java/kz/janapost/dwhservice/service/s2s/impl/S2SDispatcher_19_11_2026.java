package kz.janapost.dwhservice.service.s2s.impl;

import jakarta.annotation.PostConstruct;
import kz.janapost.dwhservice.config.properties.S2SProperties;
import kz.janapost.dwhservice.exception.S2SDeliveryException;
import kz.janapost.dwhservice.model.s2s.S2SEvent;
import kz.janapost.dwhservice.model.s2s.S2SIdentity;
import kz.janapost.dwhservice.service.s2s.S2SDispatcher;
import kz.janapost.dwhservice.service.s2s.S2SService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Провайдеры — Spring-бины {@link S2SService} по имени (как {@code Map<String, PhoneValidationMethodSender<?>>}
 * в client-service). Какие из них активны — {@code kz.janapost.dwh-service.s2s.default-bean} и {@code parallel-beans}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S2SDispatcher_19_11_2026 implements S2SDispatcher {
    private final Map<String, S2SService> s2sServices;
    private final S2SProperties properties;

    /** Опечатка в имени провайдера — сервис не стартует: иначе события молча не уходили бы никуда. */
    @PostConstruct
    void validate() {
        for (String bean : activeBeanNames()) {
            if (!s2sServices.containsKey(bean)) {
                throw new IllegalStateException("Unknown S2S provider bean '" + bean + "' in kz.janapost.dwh-service.s2s."
                        + "default-bean / parallel-beans. Available: " + s2sServices.keySet());
            }
        }
    }

    @Override
    public void send(S2SEvent event) {
        forEach(activeServices(), service -> service.send(event), "send");
    }

    @Override
    public void saveIdentity(S2SIdentity identity) {
        forEach(s2sServices.values(), service -> service.saveIdentity(identity), "saveIdentity");
    }

    @Override
    public List<S2SService> activeServices() {
        return activeBeanNames().stream().map(s2sServices::get).toList();
    }

    private Set<String> activeBeanNames() {
        Set<String> names = new LinkedHashSet<>();
        names.add(properties.getDefaultBean() == null ? "" : properties.getDefaultBean().trim());
        if (properties.getParallelBeans() != null) {
            properties.getParallelBeans().stream()
                    .filter(name -> name != null && !name.isBlank())
                    .map(String::trim)
                    .forEach(names::add);
        }
        return names;
    }

    /** Каждый провайдер — в своём try/catch: ошибка одного не мешает другому (повтор остальным безопасен). */
    private static void forEach(Collection<S2SService> services, Consumer<S2SService> action, String operation) {
        List<String> failed = new ArrayList<>();
        RuntimeException firstError = null;
        for (S2SService service : services) {
            try {
                action.accept(service);
            } catch (RuntimeException e) {
                log.warn("[S2S:{}] {} failed: {}", service.beanName(), operation, e.toString());
                failed.add(service.beanName());
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        if (firstError != null) {
            throw new S2SDeliveryException(failed, firstError);
        }
    }
}
