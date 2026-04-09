package com.DeliveryInventoryService.DeliveryInventoryService.Utils.cronjob;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.CityRouteEtaEntry;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.InterCityRouteService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled job that precomputes travel time for every ordered city pair
 * and writes results to Redis.
 *
 * Runs every 30 minutes (configured via application.properties).
 * Skips pairs where no route covers both cities (logs a warning).
 *
 * Key written: eta:city:{origin}:{destination}
 * TTL: 35 minutes (defined in Constant.TTL — slightly longer than cadence
 * so there is never a gap between job runs where keys are expired)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterCityEtaRefreshJob {

    private final WarehouseRepository warehouseRepository;
    private final InterCityRouteService interCityRouteService;

    /**
     * Cron: every 30 minutes.
     * Override via: myapp.cron.inter_city_eta_refresh in application.properties
     */
    @Scheduled(cron = "${myapp.cron.inter_city_eta_refresh:0 */30 * * * *}")
    public void refreshAllCityPairs() {
        log.info("═══════════════════════════════════════════════════════");
        log.info("  InterCity ETA Refresh Job — START");
        log.info("═══════════════════════════════════════════════════════");

        List<String> cities = warehouseRepository.findAllDistinctCities();

        if (cities.isEmpty()) {
            log.warn("No cities found in warehouse table — nothing to index.");
            return;
        }

        int totalPairs = cities.size() * (cities.size() - 1);
        log.info("Cities: {} | Pairs to compute: {}", cities.size(), totalPairs);

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger noRoute = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (String origin : cities) {
            for (String dest : cities) {
                if (origin.equals(dest))
                    continue;

                try {
                    CityRouteEtaEntry entry = interCityRouteService.computeAndCache(origin, dest);

                    if (entry != null) {
                        success.incrementAndGet();
                        log.debug("  ✓ {} → {} | {}km | {}s",
                                origin, dest,
                                String.format("%.1f", entry.getTotalDistanceKm()),
                                entry.getTotalTravelTimeSeconds());
                    } else {
                        noRoute.incrementAndGet();
                        log.debug("  ~ {} → {} | no direct route found", origin, dest);
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.error("  ✗ {} → {} | error: {}", origin, dest, e.getMessage());
                }
            }
        }

        log.info("═══════════════════════════════════════════════════════");
        log.info("  InterCity ETA Refresh Job — DONE");
        log.info("  Total pairs : {}", totalPairs);
        log.info("  Cached OK   : {}", success.get());
        log.info("  No route    : {}", noRoute.get());
        log.info("  Errors      : {}", failed.get());
        log.info("═══════════════════════════════════════════════════════");
    }
}