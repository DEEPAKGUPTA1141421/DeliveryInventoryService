package com.DeliveryInventoryService.DeliveryInventoryService.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Publishes parcel lifecycle events to Kafka.
 * Partition key = parcelId to guarantee ordered processing per parcel.
 */
@Component
@Slf4j
public class ParcelLifecycleProducer {

    private final KafkaTemplate<String, ParcelLifecycleEvent> kafkaTemplate;
    private final String topic;

    public ParcelLifecycleProducer(
            KafkaTemplate<String, ParcelLifecycleEvent> kafkaTemplate,
            @Value("${app.kafka.topic.parcel-lifecycle:parcel.lifecycle.events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(ParcelLifecycleEvent event) {
        String key = Objects.requireNonNull(event.getParcelId()).toString();
        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Failed to publish ParcelLifecycleEvent parcelId={} type={}: {}",
                                event.getParcelId(), event.getEventType(), ex.getMessage());
                    } else {
                        log.debug("Published {} for parcel {}",
                                event.getEventType(), event.getParcelId());
                    }
                });
    }
}
