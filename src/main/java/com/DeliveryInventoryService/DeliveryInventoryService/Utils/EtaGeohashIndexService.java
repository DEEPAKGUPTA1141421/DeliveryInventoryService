package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.GeohashEtaEntry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

import static com.DeliveryInventoryService.DeliveryInventoryService.Utils.constant.Constant.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class EtaGeohashIndexService {

    private final WarehouseRepository warehouseRepository;
    private final OsrmDistanceMatrix osrmDistanceMatrix;
    private final RedisTemplate<String, Object> etaRedisTemplate;

    private static final int BATCH_SIZE = 50; // 🔥 tune this

    public IndexResult indexCity(String city) {

        List<Warehouse> warehouses = warehouseRepository.findByCity(city);

        if (warehouses.isEmpty()) {
            log.warn("Skipping city={} — no warehouses found", city);
            return new IndexResult(city, 0, 0, 0);
        }

        // ── Bounding box ─────────────────────────────────────────────
        double minLat = warehouses.stream().mapToDouble(Warehouse::getLat).min().orElseThrow() - BBOX_PADDING_DEG;
        double maxLat = warehouses.stream().mapToDouble(Warehouse::getLat).max().orElseThrow() + BBOX_PADDING_DEG;
        double minLng = warehouses.stream().mapToDouble(Warehouse::getLng).min().orElseThrow() - BBOX_PADDING_DEG;
        double maxLng = warehouses.stream().mapToDouble(Warehouse::getLng).max().orElseThrow() + BBOX_PADDING_DEG;

        log.info("City={} | bbox=[{},{} → {},{}] | warehouses={}",
                city, minLat, minLng, maxLat, maxLng, warehouses.size());

        List<String> cells = GeohashUtils.coverBoundingBox(
                minLat, minLng, maxLat, maxLng, GEOHASH_PRECISION);

        log.info("City={} | {} geohash cells to index", city, cells.size());

        int success = 0, failed = 0;

        for (int i = 0; i < cells.size(); i += BATCH_SIZE) {

            List<String> batch = cells.subList(i, Math.min(i + BATCH_SIZE, cells.size()));

            try {

                List<double[]> sources = new ArrayList<>();
                List<String> geohashes = new ArrayList<>();

                for (String g : batch) {
                    double[] center = GeohashUtils.decode(g);
                    sources.add(new double[] { center[1], center[0] }); // lng, lat
                    geohashes.add(g);
                }

                List<double[]> destinations = warehouses.stream()
                        .map(w -> new double[] { w.getLng(), w.getLat() })
                        .toList();

                var matrix = osrmDistanceMatrix.getDistanceMatrix(sources, destinations);

                if (matrix == null) {
                    log.warn("Matrix API failed for batch {}", i);
                    failed += batch.size();
                    continue;
                }

                double[][] durations = matrix.durations();
                double[][] distances = matrix.distances();

                for (int s = 0; s < sources.size(); s++) {

                    try {
                        int bestIdx = -1;
                        double bestTime = Double.MAX_VALUE;

                        for (int d = 0; d < destinations.size(); d++) {
                            double time = durations[s][d];

                            if (time > 0 && time < bestTime) {
                                bestTime = time;
                                bestIdx = d;
                            }
                        }

                        if (bestIdx == -1) {
                            failed++;
                            continue;
                        }

                        Warehouse nearest = warehouses.get(bestIdx);

                        double roadMetres = distances[s][bestIdx];
                        long travelSecs = (long) durations[s][bestIdx];

                        double[] center = GeohashUtils.decode(geohashes.get(s));
                        double straightKm = GeoUtils.distanceKm(
                                center[0], center[1],
                                nearest.getLat(), nearest.getLng());

                        GeohashEtaEntry entry = new GeohashEtaEntry(
                                nearest.getId(),
                                nearest.getName(),
                                straightKm,
                                travelSecs,
                                roadMetres,
                                Instant.now().getEpochSecond());

                        // 🔥 Redis write
                        // etaRedisTemplate.opsForValue().set(EtaRedisKeys.userKey(geohashes.get(s)),
                        // entry, TTL);

                        success++;

                    } catch (Exception e) {
                        failed++;
                    }
                }

            } catch (Exception e) {
                log.error("Batch failed at {}: {}", i, e.getMessage());
                failed += batch.size();
            }
        }

        log.info("City={} | done — success={}, failed={}, total={}",
                city, success, failed, cells.size());

        return new IndexResult(city, cells.size(), success, failed);
    }

    public record IndexResult(String city, int total, int success, int failed) {
    }
}