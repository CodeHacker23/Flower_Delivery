package org.example.flower_delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FlowerDeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowerDeliveryApplication.class, args);
    }

}
