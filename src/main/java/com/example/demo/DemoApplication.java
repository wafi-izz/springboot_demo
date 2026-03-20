package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Value;

@SpringBootApplication
public class DemoApplication {

    @Value("${server.port:8080}")
    private int port;

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openSwagger() {
        try {
            Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "http://localhost:" + port + "/swagger-ui/index.html"});
        } catch (Exception ignored) {
        }
    }
}
