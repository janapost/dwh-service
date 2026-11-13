package kz.janapost.dwhservice.testsupport;

import org.junit.jupiter.api.extension.Extension;

/**
 * Пинует версию Docker API для docker-java (Testcontainers) до первой проверки Docker.
 * <p>
 * Docker Engine 29.x (API 1.40–1.54) отвечает HTTP 400 на версию API, которую docker-java запрашивает
 * по умолчанию, и Testcontainers считает, что Docker недоступен.
 * <p>
 * {@code static}-блок в самом тестовом классе (как в client-service) с {@code @Testcontainers(disabledWithoutDocker = true)}
 * не работает: проверка Docker идёт на этапе условий JUnit, когда класс теста ещё не инициализирован, — и тесты
 * молча пропускаются. Расширения JUnit создаёт раньше проверки условий, поэтому настройка успевает.
 * <p>
 * Использование: {@code @ExtendWith(DockerApiVersionExtension.class)} над {@code @Testcontainers}.
 * Явный {@code -Dapi.version=...} не перезаписывается.
 */
public final class DockerApiVersionExtension implements Extension {
    static final String API_VERSION = "1.43";

    static {
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", API_VERSION);
        }
    }
}
