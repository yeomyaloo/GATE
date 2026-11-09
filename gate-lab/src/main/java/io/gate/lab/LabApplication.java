package io.gate.lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** gate-lab: 일부러 취약하게 만든 실습용 앱. 외부 공개 배포 금지. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LabApplication {

    public static void main(String[] args) {
        SpringApplication.run(LabApplication.class, args);
    }
}
