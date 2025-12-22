package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;

public class KMeansClustering {

    private static final Logger logger = LoggerFactory.getLogger(KMeansClustering.class);

    private int k;
    private int maxIterations;
    private final Random random = new Random();

    @Autowired
    private WarehouseRepository warehouseRepository;

    public KMeansClustering(int k, int maxIterations) {
        this.k = k;
        this.maxIterations = maxIterations;
        logger.info("KMeansClustering initialized with k={} and maxIterations={}", k, maxIterations);
    }

    // ---------------------------------------------------------------
    // CLUSTERING
    // ---------------------------------------------------------------
    public Map<Integer, List<Order>> clusterOrders(List<Order> orders) {
        logger.info("Starting KMeans Clustering for {} orders...", orders.size());

        int n = orders.size();
        if (n == 0 || k <= 0) {
            logger.error("Invalid clustering input: orders={}, k={}", n, k);
            throw new IllegalArgumentException("Invalid input: no orders or clusters");
        }

        // STEP 1: INITIALIZE CENTROIDS
        logger.info("Selecting {} random centroids...", k);
        List<double[]> centroids = new ArrayList<>();
        Set<Integer> used = new HashSet<>();

        while (centroids.size() < k) {
            int idx = random.nextInt(n);
            if (!used.contains(idx)) {
                used.add(idx);
                centroids.add(new double[] {
                        orders.get(idx).getOriginLat(),
                        orders.get(idx).getOriginLng()
                });
                logger.debug("Centroid {} initialized at lat={}, lng={}",
                        centroids.size() - 1,
                        orders.get(idx).getOriginLat(),
                        orders.get(idx).getOriginLng());
            }
        }

        Map<Integer, List<Order>> clusters = new HashMap<>();

        // STEP 2: ITERATE
        logger.info("Running clustering iterations up to {} max cycles", maxIterations);

        for (int iter = 0; iter < maxIterations; iter++) {
            logger.debug("Iteration {}", iter + 1);
            clusters.clear();
            for (int i = 0; i < k; i++)
                clusters.put(i, new ArrayList<>());

            // Assignment step
            for (Order order : orders) {
                int clusterId = nearestCentroid(order, centroids);
                clusters.get(clusterId).add(order);
            }

            // Update centroids
            List<double[]> newCentroids = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                List<Order> clusterOrders = clusters.get(i);
                if (clusterOrders.isEmpty()) {
                    logger.warn("Cluster {} empty, retaining old centroid.", i);
                    newCentroids.add(centroids.get(i));
                    continue;
                }

                double sumLat = 0, sumLng = 0;
                for (Order o : clusterOrders) {
                    sumLat += o.getOriginLat();
                    sumLng += o.getOriginLng();
                }
                newCentroids.add(new double[] {
                        sumLat / clusterOrders.size(),
                        sumLng / clusterOrders.size()
                });
            }

            // Check convergence
            boolean converged = true;
            for (int i = 0; i < k; i++) {
                double movement = distance(centroids.get(i)[0], centroids.get(i)[1], newCentroids.get(i)[0],
                        newCentroids.get(i)[1]);
                logger.debug("Centroid {} shift distance = {}", i, movement);
                if (movement > 1e-6)
                    converged = false;
            }

            centroids = newCentroids;
            if (converged) {
                logger.info("Convergence reached at iteration {}", iter + 1);
                break;
            }
        }

        logger.info("KMeans clustering completed.");
        clusters.forEach((key, value) -> logger.info("Cluster {} contains {} orders.", key, value.size()));

        return clusters;
    }

    // ---------------------------------------------------------------
    // VRP EXECUTION
    // ---------------------------------------------------------------
    public Map<Integer, Map<Integer, List<Integer>>> clusterAndSolveVRP(
            List<Order> orders,
            double[][] distanceMatrix,
            int riderCapacityKg,
            String city) throws Exception {

        logger.info("Starting cluster + VRP pipeline for city={} with rider capacity={}kg", city, riderCapacityKg);

        Map<Integer, List<Order>> clusters = clusterOrders(orders);

        logger.info("Total clusters generated = {}", clusters.size());

        Map<Integer, Map<Integer, List<Integer>>> clusterRoutes = new HashMap<>();

        for (Map.Entry<Integer, List<Order>> entry : clusters.entrySet()) {

            int clusterId = entry.getKey();
            List<Order> clusterOrders = entry.getValue();

            if (clusterOrders.isEmpty()) {
                logger.warn("Cluster {} empty. Skipping.", clusterId);
                continue;
            }

            logger.info("Processing VRP for cluster {} with {} orders…", clusterId, clusterOrders.size());

            int minRiders = MinimumRiderToFullFillTheRequest(clusterOrders, riderCapacityKg);

            logger.info("Cluster {} requires minimum {} riders", clusterId, minRiders);

            if (minRiders == -1) {
                logger.error("Cluster {} cannot be fulfilled due to configuration failure", clusterId);
                throw new Exception("Cannot solve due to invalid rider requirement");
            }

            Warehouse depot = findWareHouseOfCity(clusterOrders, city);

            logger.debug("Selected warehouse {} at lat={} lng={}", depot.getId(), depot.getLat(), depot.getLng());

            VRPCapacitySolver solver = new VRPCapacitySolver(
                    distanceMatrix,
                    clusterOrders,
                    riderCapacityKg,
                    minRiders,
                    0);

            Map<Integer, List<Integer>> routes = solver.solve();

            logger.info("VRP complete for cluster {}. Assigned riders={}", clusterId, routes.size());

            clusterRoutes.put(clusterId, routes);
        }

        logger.info("All clusters processed. VRP pipeline finished.");

        return clusterRoutes;
    }

    // ---------------------------------------------------------------
    // UTILITY + SUPPORT FUNCTIONS
    // ---------------------------------------------------------------
    private int nearestCentroid(Order order, List<double[]> centroids) {
        int clusterId = -1;
        double minDist = Double.MAX_VALUE;

        for (int i = 0; i < centroids.size(); i++) {
            double[] c = centroids.get(i);
            double d = distance(order.getOriginLat(), order.getOriginLng(), c[0], c[1]);
            if (d < minDist) {
                minDist = d;
                clusterId = i;
            }
        }
        return clusterId;
    }

    private double distance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = lat1 - lat2;
        double dLon = lon1 - lon2;
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }

    public int MinimumRiderToFullFillTheRequest(List<Order> orders, int riderCapacityKg) {
        int min_rider = 1;
        int max_rider = orders.size();
        int ans = -1;

        while (min_rider <= max_rider) {
            int mid = min_rider + (max_rider - min_rider) / 2;
            if (isPossible(mid, orders, riderCapacityKg)) {
                ans = mid;
                max_rider = mid - 1; // try fewer riders
            } else {
                min_rider = mid + 1; // need more riders
            }
        }
        return ans;
    }

    /**
     * Check if `mid` riders are enough to handle all orders within capacity.
     */
    private boolean isPossible(int mid, List<Order> orders, int riderCapacityKg) {
        // Total capacity available
        double totalCapacity = mid * riderCapacityKg;
        double totalWeight = orders.stream().mapToDouble(Order::getWeightKg).sum();
        if (totalWeight > totalCapacity) {
            return false;
        }
        for (Order o : orders) {
            if (o.getWeightKg() > riderCapacityKg) {
                return false;
            }
        }
        return true;
    }

    private Warehouse findWareHouseOfCity(List<Order> clusterOrders, String city) {
        logger.info("Finding warehouse for city={}", city);

        List<Warehouse> wareHousePerCity = warehouseRepository.findByCity(city);

        if (wareHousePerCity.isEmpty()) {
            logger.error("No warehouses found for city={}", city);
            throw new IllegalArgumentException("No WareHouse For That City");
        }
        if (wareHousePerCity.size() == 1) {
            logger.debug("Only one warehouse exists — using warehouse={}", wareHousePerCity.get(0).getId());
            return wareHousePerCity.get(0);
        }

        return findAverageNearestWareHouse(clusterOrders, wareHousePerCity);
    }

    private Warehouse findAverageNearestWareHouse(List<Order> clusterOrders, List<Warehouse> wareHousePerCity) {
        logger.info("Finding nearest warehouse to cluster centroid...");

        double avgLat = clusterOrders.stream().mapToDouble(Order::getOriginLat).average().orElse(0);
        double avgLng = clusterOrders.stream().mapToDouble(Order::getOriginLng).average().orElse(0);

        Warehouse nearestWarehouse = null;
        double minDistance = Double.MAX_VALUE;

        for (Warehouse wh : wareHousePerCity) {
            double distance = GeoUtils.distanceKm(avgLat, avgLng, wh.getLat(), wh.getLng());
            if (distance < minDistance) {
                minDistance = distance;
                nearestWarehouse = wh;
            }
        }

        logger.debug("Nearest warehouse selected: id={}, distance={}", nearestWarehouse.getId(), minDistance);

        return nearestWarehouse;
    }
}
