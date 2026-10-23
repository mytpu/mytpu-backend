package org.mytpu.assistant;

import org.mytpu.assistant.config.RequiredSettings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AssistantApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AssistantApplication.class);
        application.addListeners(new RequiredSettings());
        application.run(args);
    }
}
