package com.depromeet.piki.extractor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExtractorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExtractorApplication.class, args);
    }
}
