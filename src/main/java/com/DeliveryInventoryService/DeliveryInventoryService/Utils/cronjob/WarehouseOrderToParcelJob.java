package com.DeliveryInventoryService.DeliveryInventoryService.Utils.cronjob;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.CreateParcelRequest;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order.OrderStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ParcelRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.ParcelService;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * WarehouseOrderToParcelJob
 * ──────────────────────────
 * Once orders have been physically } set),
 * they sit there waiting to be wrapped into Parcels for downstream shipment.
 *received at a warehouse via the rider
 * handover flow ({@code Order.status = WAREHOUSE}, {@code wareHouseId
 * This cron does that batching. It runs every 5 minutes by default, finds all
 * warehouses that have at least one parcel-less WAREHOUSE-status order, and
 * creates a Parcel per order.
 *
 * Override the schedule via:
 * myapp.cron.batch_warehouse_orders_to_parcels=0 *\/5 * * * *
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WarehouseOrderToParcelJob {

    private final OrderRepository orderRepository;
    private final ParcelRepository parcelRepository;
    private final WarehouseRepository warehouseRepository;
    private final ParcelService parcelService;

    @Scheduled(cron = "${myapp.cron.batch_warehouse_orders_to_parcels:0 */5 * * * *}")
    public void batchOrdersToParcels() {
        List<UUID> warehouseIds = orderRepository.findDistinctWareHouseIdsByStatus(OrderStatus.ASSIGNED);

        if (warehouseIds.isEmpty()) {
            log.info("Parcel-batch cron: no WAREHOUSE-status orders pending");
            return;
        }

        int totalCreated = 0, totalSkipped = 0;
        for (UUID warehouseId : warehouseIds) {
            List<Order> orders = orderRepository.findUnassignedByWarehouse(
                    warehouseId, OrderStatus.ASSIGNED);
            if (orders.isEmpty())
                continue;

            int created = 0, skipped = 0;
            for (Order order : orders) {
                try {
                    if (parcelRepository.findByOrderId(order.getId()).isPresent()) {
                        skipped++;
                        continue;
                    }
                    UUID destWarehouse = nearestWarehouse(order.getDestLat(), order.getDestLng());
                    if (destWarehouse == null) {
                        log.warn("No active warehouse near dest of order {} — skipping", order.getOrderNo());
                        skipped++;
                        continue;
                    }

                    CreateParcelRequest req = new CreateParcelRequest();
                    req.setOrderId(order.getId());
                    req.setWeightKg(order.getWeightKg());
                    req.setOriginWarehouseId(warehouseId);
                    req.setDestinationWarehouseId(destWarehouse);
                    req.setDescription("Auto-batched from order " + order.getOrderNo());

                    ApiResponse<Object> resp = parcelService.createParcel(req);
                    if (resp.success()) {
                        order.setStatus(OrderStatus.PARCEL_CREATED);
                        orderRepository.save(order);
                        created++;
                    } else {
                        log.warn("Parcel-batch: order {} skipped — {}", order.getOrderNo(), resp.message());
                        skipped++;
                    }
                } catch (Exception e) {
                    log.warn("Parcel-batch: order {} failed — {}", order.getOrderNo(), e.getMessage());
                    skipped++;
                }
            }

            log.info("Parcel-batch warehouse {}: {} created, {} skipped",
                    warehouseId, created, skipped);
            totalCreated += created;
            totalSkipped += skipped;
        }

        log.info("Parcel-batch cron complete: {} parcels created across {} warehouses ({} skipped)",
                totalCreated, warehouseIds.size(), totalSkipped);
    }

    private UUID nearestWarehouse(double lat, double lng) {
        return warehouseRepository.findAll().stream()
                .filter(w -> w.getStatus() == Warehouse.WarehouseStatus.ACTIVE)
                .min(Comparator.comparingDouble(
                        w -> GeoUtils.distanceKm(w.getLat(), w.getLng(), lat, lng)))
                .map(Warehouse::getId)
                .orElse(null);
    }
}
// hkuuhjhjhuhuuh hhuh