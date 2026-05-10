package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class KMeansClustering {

    private static final Logger logger = LoggerFactory.getLogger(KMeansClustering.class);

    private static final int DEFAULT_K_DENOMINATOR = 12; // target ~12 stops per cluster
    private static final int MAX_ITERATIONS = 100;
    private static final int MIN_K = 1;

    // OR-Tools is always the primary solver. VRPCapacitySolver is kept only as
    // a fallback for when OR-Tools native libraries are unavailable at runtime.

    private final WarehouseRepository warehouseRepository;
    private final Random random = new Random();

    // ---------------------------------------------------------------
    // CLUSTERING
    // ---------------------------------------------------------------

    /**
     * Clusters orders using KMeans++ initialisation.
     * k is computed as ceil(orders.size / DEFAULT_K_DENOMINATOR), capped at orders.size.
     * All distances use Haversine so centroids are geographically correct.
     */
    public Map<Integer, List<Order>> clusterOrders(List<Order> orders) {
        int n = orders.size();
        if (n == 0) throw new IllegalArgumentException("Cannot cluster empty order list");

        int k = Math.max(MIN_K, (int) Math.ceil((double) n / DEFAULT_K_DENOMINATOR));
        k = Math.min(k, n);

        logger.info("KMeans: {} orders → k={} clusters (target {}/cluster)", n, k, DEFAULT_K_DENOMINATOR);

        List<double[]> centroids = initKMeansPlusPlus(orders, k);
        Map<Integer, List<Order>> clusters = new HashMap<>();

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            clusters.clear();
            for (int i = 0; i < k; i++) clusters.put(i, new ArrayList<>());

            for (Order order : orders) {
                int closest = nearestCentroidIndex(order, centroids);
                clusters.get(closest).add(order);
            }

            List<double[]> newCentroids = recomputeCentroids(clusters, centroids, k);

            if (hasConverged(centroids, newCentroids)) {
                logger.info("KMeans converged at iteration {}", iter + 1);
                break;
            }
            centroids = newCentroids;
        }

        clusters.forEach((id, list) ->
                logger.info("Cluster {} → {} orders", id, list.size()));
        return clusters;
    }

    // ---------------------------------------------------------------
    // VRP PIPELINE ENTRY POINT
    // ---------------------------------------------------------------

    public Map<Integer, Map<Integer, List<Order>>> clusterAndSolveVRP(
            List<Order> orders,
            double[][] distanceMatrix,
            int riderCapacityKg,
            String city) throws Exception {

        logger.info("VRP pipeline: city={}, orders={}, capacity={}kg", city, orders.size(), riderCapacityKg);

        Map<Integer, List<Order>> clusters = clusterOrders(orders);
        Map<Integer, Map<Integer, List<Order>>> clusterRoutes = new HashMap<>();

        for (Map.Entry<Integer, List<Order>> entry : clusters.entrySet()) {
            int clusterId = entry.getKey();
            List<Order> clusterOrders = entry.getValue();

            if (clusterOrders.isEmpty()) {
                logger.warn("Cluster {} is empty — skipping", clusterId);
                continue;
            }

            int minRiders = minimumRidersRequired(clusterOrders, riderCapacityKg);
            if (minRiders == -1) throw new Exception("Cannot satisfy capacity constraints for cluster " + clusterId);

            logger.info("Cluster {} → {} orders, {} riders needed", clusterId, clusterOrders.size(), minRiders);

            Warehouse depot = findDepotForCluster(clusterOrders, city);

            // Single-stop clusters: OR-Tools treats node 0 as both depot and delivery,
            // producing a valid but empty solution. Skip the solver entirely.
            if (clusterOrders.size() == 1) {
                logger.info("Cluster {} (1 stop) → trivial assignment, no VRP needed", clusterId);
                Map<Integer, List<Order>> trivial = new HashMap<>();
                trivial.put(0, new ArrayList<>(clusterOrders));
                clusterRoutes.put(clusterId, trivial);
                continue;
            }

            logger.info("Cluster {} ({} stops) → OR-Tools solver", clusterId, clusterOrders.size());
            Map<Integer, List<Order>> routes = new OrToolsVrpSolver(
                    distanceMatrix, clusterOrders, riderCapacityKg, minRiders, 0).solve();

            if (routes.values().stream().allMatch(List::isEmpty)) {
                throw new IllegalStateException(
                        "OR-Tools returned no routes for cluster=" + clusterId +
                        " (" + clusterOrders.size() + " stops). Check native library installation.");
            }

            logger.info("Cluster {} VRP complete: {} routes", clusterId, routes.size());

            clusterRoutes.put(clusterId, routes);
        }

        logger.info("VRP pipeline finished for city={}", city);
        return clusterRoutes;
    }

    // ---------------------------------------------------------------
    // INTERNALS
    // ---------------------------------------------------------------

    /** KMeans++ initialisation — spreads initial centroids apart. */
    private List<double[]> initKMeansPlusPlus(List<Order> orders, int k) {
        List<double[]> centroids = new ArrayList<>();
        // Pick first centroid at random
        Order first = orders.get(random.nextInt(orders.size()));
        centroids.add(new double[]{first.getOriginLat(), first.getOriginLng()});

        for (int c = 1; c < k; c++) {
            double[] distances = new double[orders.size()];
            double total = 0;
            for (int i = 0; i < orders.size(); i++) {
                double minDist = Double.MAX_VALUE;
                for (double[] centroid : centroids) {
                    double d = GeoUtils.distanceKm(orders.get(i).getOriginLat(), orders.get(i).getOriginLng(),
                            centroid[0], centroid[1]);
                    minDist = Math.min(minDist, d * d); // squared for probability weighting
                }
                distances[i] = minDist;
                total += minDist;
            }
            // Weighted random selection
            double threshold = random.nextDouble() * total;
            double cumulative = 0;
            int chosen = orders.size() - 1;
            for (int i = 0; i < orders.size(); i++) {
                cumulative += distances[i];
                if (cumulative >= threshold) { chosen = i; break; }
            }
            Order pick = orders.get(chosen);
            centroids.add(new double[]{pick.getOriginLat(), pick.getOriginLng()});
        }
        return centroids;
    }

    private int nearestCentroidIndex(Order order, List<double[]> centroids) {
        int best = 0;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < centroids.size(); i++) {
            double d = GeoUtils.distanceKm(order.getOriginLat(), order.getOriginLng(),
                    centroids.get(i)[0], centroids.get(i)[1]);
            if (d < minDist) { minDist = d; best = i; }
        }
        return best;
    }

    private List<double[]> recomputeCentroids(Map<Integer, List<Order>> clusters,
                                               List<double[]> oldCentroids, int k) {
        List<double[]> newCentroids = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            List<Order> members = clusters.get(i);
            if (members == null || members.isEmpty()) {
                newCentroids.add(oldCentroids.get(i)); // retain old
                continue;
            }
            double sumLat = members.stream().mapToDouble(Order::getOriginLat).sum();
            double sumLng = members.stream().mapToDouble(Order::getOriginLng).sum();
            newCentroids.add(new double[]{sumLat / members.size(), sumLng / members.size()});
        }
        return newCentroids;
    }

    private boolean hasConverged(List<double[]> oldCentroids, List<double[]> newCentroids) {
        for (int i = 0; i < oldCentroids.size(); i++) {
            if (GeoUtils.distanceKm(oldCentroids.get(i)[0], oldCentroids.get(i)[1],
                    newCentroids.get(i)[0], newCentroids.get(i)[1]) > 0.01) { // 10m threshold
                return false;
            }
        }
        return true;
    }

    /** Binary search for minimum riders needed to satisfy total weight and per-order constraints. */
    public int minimumRidersRequired(List<Order> orders, int riderCapacityKg) {
        int lo = 1, hi = orders.size(), ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (canFulfill(mid, orders, riderCapacityKg)) { ans = mid; hi = mid - 1; }
            else lo = mid + 1;
        }
        return ans;
    }

    private boolean canFulfill(int riders, List<Order> orders, int capacityKg) {
        double totalWeight = orders.stream().mapToDouble(Order::getWeightKg).sum();
        if (totalWeight > (double) riders * capacityKg) return false;
        return orders.stream().noneMatch(o -> o.getWeightKg() > capacityKg);
    }

    private Warehouse findDepotForCluster(List<Order> orders, String city) {
        List<Warehouse> warehouses = warehouseRepository.findByCity(city);
        if (warehouses.isEmpty()) throw new IllegalArgumentException("No warehouse found for city: " + city);
        if (warehouses.size() == 1) return warehouses.get(0);

        double avgLat = orders.stream().mapToDouble(Order::getOriginLat).average().orElse(0);
        double avgLng = orders.stream().mapToDouble(Order::getOriginLng).average().orElse(0);

        return warehouses.stream()
                .min(Comparator.comparingDouble(
                        wh -> GeoUtils.distanceKm(avgLat, avgLng, wh.getLat(), wh.getLng())))
                .orElseThrow();
    }
}
