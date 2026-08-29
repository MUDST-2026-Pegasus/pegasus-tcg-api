package com.pegasus.pegasustcgapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PegasusTcgApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PegasusTcgApiApplication.class, args);
    }
}
