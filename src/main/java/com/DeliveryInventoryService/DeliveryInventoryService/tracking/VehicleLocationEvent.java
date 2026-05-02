package com.DeliveryInventoryService.DeliveryInventoryService.tracking;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Kafka message published every time a vehicle reports its GPS position.
 * Partitioned by routeId so all pings for the same route are processed in order.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VehicleLocationEvent {

    private UUID routeId;
    private UUID vehicleId;

    /** Sequence of the RoutePoint the vehicle is currently traveling FROM. */
    private int currentFromSequence;

    private double lat;
    private double lon;
    private LocalDateTime timestamp;
}
