package com.DeliveryInventoryService.DeliveryInventoryService.tracking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Publishes vehicle GPS pings to Kafka.
 * Partition key = routeId — guarantees ordering of pings per route.
 */
@Component
@Slf4j
public class VehicleLocationProducer {

    private final KafkaTemplate<String, VehicleLocationEvent> kafkaTemplate;
    private final String topic;

    public VehicleLocationProducer(
            KafkaTemplate<String, VehicleLocationEvent> kafkaTemplate,
            @Value("${app.kafka.topic.vehicle-location:vehicle.location.update}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(VehicleLocationEvent event) {
        String key = Objects.requireNonNull(event.getRouteId()).toString();
        kafkaTemplate.send(Objects.requireNonNull(topic), key, Objects.requireNonNull(event))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Failed to publish location event for route {}: {}",
                                event.getRouteId(), ex.getMessage());
                    }
                });
    }
}
