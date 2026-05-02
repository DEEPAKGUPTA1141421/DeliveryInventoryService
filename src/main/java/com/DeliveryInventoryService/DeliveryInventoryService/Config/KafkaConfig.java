package com.DeliveryInventoryService.DeliveryInventoryService.Config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${app.kafka.topic.vehicle-location:vehicle.location.update}")
    private String vehicleLocationTopic;

    /**
     * 6 partitions — one per active vehicle group; allows 6 consumer instances to
     * process location pings in parallel without violating per-route ordering
     * (producers key by routeId, so all pings for a route land on the same partition).
     */
    @Bean
    public NewTopic vehicleLocationTopic() {
        return TopicBuilder.name(vehicleLocationTopic)
                .partitions(6)
                .replicas(1)
                .build();
    }
}
