package com.DeliveryInventoryService.DeliveryInventoryService.Utils.cronjob;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.EtaGeohashIndexService;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.EtaGeohashIndexService.IndexResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class EtaGeohashRefreshJob {
    private final WarehouseRepository warehouseRepository;
    private final EtaGeohashIndexService indexService;

    @Scheduled(cron = "0 */2 * * * *")
    public void refreshAllCities() {
        log.info("══════════════════════════════════════════════════");
        log.info("  ETA Geohash Refresh Job — START");
        log.info("══════════════════════════════════════════════════");
        // ── Step 1: fetch unique city names from the warehouse table ──────────
        List<String> cities = warehouseRepository.findAllDistinctCities();
        if (cities.isEmpty()) {
            log.warn("No cities found in warehouse table — nothing to index.");
            return;
        }
        log.info("Cities to index ({}): {}", cities.size(), cities);

        // ── Step 2: index each city ───────────────────────────────────────────
        int citiesOk = 0, citiesFailed = 0;
        int totalCells = 0, totalSuccess = 0, totalFailed = 0;
        int i = 0;
        for (String city : cities) {
            if (i != 0)
                return;
            i++;
            try {
                log.info("── Indexing city: {} ─────────────────────────────", city);
                IndexResult result = indexService.indexCity(city);

                totalCells += result.total();
                totalSuccess += result.success();
                totalFailed += result.failed();

                if (result.total() == 0) {
                    log.warn("  {} → skipped (no warehouses)", city);
                } else {
                    log.info("  {} → {}/{} cells OK, {} failed",
                            city, result.success(), result.total(), result.failed());
                    citiesOk++;
                }

            } catch (Exception e) {
                log.error("  {} → FAILED: {}", city, e.getMessage(), e);
                citiesFailed++;
            }
        }
        // ── Step 3: summary ───────────────────────────────────────────────────
        log.info("══════════════════════════════════════════════════");
        log.info("  ETA Geohash Refresh Job — DONE");
        log.info("  Cities:  {}/{} OK  |  {} failed",
                citiesOk, cities.size(), citiesFailed);
        log.info("  Cells:   {}/{} indexed  |  {} failed",
                totalSuccess, totalCells, totalFailed);
        log.info("══════════════════════════════════════════════════");
    }
}
// every 2 minutes}
