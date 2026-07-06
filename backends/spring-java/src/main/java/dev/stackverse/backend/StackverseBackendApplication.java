package dev.stackverse.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StackverseBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(StackverseBackendApplication.class, args);
    }
}
