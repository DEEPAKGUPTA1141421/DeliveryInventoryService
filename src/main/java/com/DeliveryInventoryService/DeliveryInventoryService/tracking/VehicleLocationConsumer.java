package com.DeliveryInventoryService.DeliveryInventoryService.tracking;

import com.DeliveryInventoryService.DeliveryInventoryService.Service.RouteDelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes vehicle GPS pings from Kafka and delegates to RouteDelayService.
 *
 * concurrency = 6 matches the topic partition count — each instance owns one
 * partition, so pings for the same route are always processed by the same
 * thread (ordered, no concurrent writes to the same RoutePoint).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VehicleLocationConsumer {

    private final RouteDelayService routeDelayService;

    @KafkaListener(
            topics     = "${app.kafka.topic.vehicle-location:vehicle.location.update}",
            groupId    = "${spring.kafka.consumer.group-id:delivery-delay-group}",
            concurrency = "6"
    )
    public void consume(
            VehicleLocationEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("Partition {} offset {} | route {} seq {} ({},{})",
                partition, offset,
                event.getRouteId(), event.getCurrentFromSequence(),
                event.getLat(), event.getLon());

        try {
            routeDelayService.processLocationUpdate(event);
        } catch (Exception e) {
            log.error("Failed processing location event route={} seq={}: {}",
                    event.getRouteId(), event.getCurrentFromSequence(), e.getMessage());
        }
    }
}
