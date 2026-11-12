package kz.janapost.dwhservice;

import lombok.NoArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@NoArgsConstructor
@EnableScheduling
@EnableAsync
public class DwhServiceApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Aqtau"));
        SpringApplication.run(DwhServiceApplication.class, args);
    }

}
