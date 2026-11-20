package kz.janapost.dwhservice;

import kz.janapost.dwhservice.testsupport.DockerApiVersionExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Контекст поднимается целиком. С UPM-2661 сервису при старте нужна Mongo: jpevent создаёт свою коллекцию
 * ({@code Event_dwh_service}). Mongo — Testcontainers; консьюмер {@code event_s2s} выключен (Kafka не нужна).
 * Требуется запущенный Docker; без него класс пропускается.
 */
@ExtendWith(DockerApiVersionExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "kz.janapost.dwh-service.s2s.consumer-enabled=false")
class DwhServiceApplicationTests {

    @Container
    @SuppressWarnings("resource")
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:6.0"));

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("dwh-service"));
    }

    @Test
    void contextLoads() {
    }

}
