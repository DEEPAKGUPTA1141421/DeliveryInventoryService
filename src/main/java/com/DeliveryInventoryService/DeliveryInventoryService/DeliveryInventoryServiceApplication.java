package com.DeliveryInventoryService.DeliveryInventoryService;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableFeignClients
@EnableAspectJAutoProxy
public class DeliveryInventoryServiceApplication {
	public static void main(String[] args) {
		SpringApplication.run(DeliveryInventoryServiceApplication.class, args);
	}
}