package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;

public class KMeansClustering {
    private int k; // number of clusters
    private int maxIterations;
    private final Random random = new Random();

    @Autowired
    private WarehouseRepository warehouseRepository;

    public KMeansClustering(int k, int maxIterations) {
        this.k = k;
        this.maxIterations = maxIterations;
    }

    public Map<Integer, List<Order>> clusterOrders(List<Order> orders) {
        int n = orders.size();
        if (n == 0 || k <= 0) {
            throw new IllegalArgumentException("Invalid input: no orders or clusters");
        }

        // Step 1: Initialize centroids randomly from orders
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
            }
        }

        Map<Integer, List<Order>> clusters = new HashMap<>();

        // Step 2: Iterate
        for (int iter = 0; iter < maxIterations; iter++) {
            clusters.clear();
            for (int i = 0; i < k; i++) {
                clusters.put(i, new ArrayList<>());
            }

            // Assign orders to nearest centroid
            for (Order order : orders) {
                int clusterId = nearestCentroid(order, centroids);
                clusters.get(clusterId).add(order);
            }

            // Update centroids
            List<double[]> newCentroids = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                List<Order> clusterOrders = clusters.get(i);
                if (clusterOrders.isEmpty()) {
                    // If a cluster is empty, keep old centroid
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

            // Check convergence (if centroids didn’t change much)
            boolean converged = true;
            for (int i = 0; i < k; i++) {
                if (distance(centroids.get(i), newCentroids.get(i)) > 1e-6) {
                    converged = false;
                    break;
                }
            }
            centroids = newCentroids;
            if (converged)
                break;
        }
        System.out.println("clustering is done");
        for (Map.Entry<Integer, List<Order>> entry : clusters.entrySet()) {
            System.out.println("Cluster " + entry.getKey() + ": " +
                    entry.getValue().size() + " orders");
        }
        return clusters;
    }

    // Haversine distance in kilometers
    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371; // Earth radius (km)
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    private double distance(double[] p1, double[] p2) {
        double dLat = p1[0] - p2[0];
        double dLon = p1[1] - p2[1];
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }

    private int nearestCentroid(Order order, List<double[]> centroids) {
        int clusterId = -1;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < centroids.size(); i++) {
            double[] c = centroids.get(i);
            double d = haversine(order.getOriginLat(), order.getOriginLng(), c[0], c[1]);
            if (d < minDist) {
                minDist = d;
                clusterId = i;
            }
        }
        return clusterId;
    }

    public Map<Integer, Map<Integer, List<Integer>>> clusterAndSolveVRP(
            List<Order> orders,
            double[][] distanceMatrix,
            int riderCapacityKg,
            String city) throws Exception {

        // Step 1: Cluster orders
        System.out.println(("2"));
        Map<Integer, List<Order>> clusters = clusterOrders(orders);
        System.out.println(("size of clusters" + clusters.size()));
        // Final result: clusterId -> (riderId -> route)
        Map<Integer, Map<Integer, List<Integer>>> clusterRoutes = new HashMap<>();
        System.out.println(("size of cluster Route" + clusterRoutes.size()));
        // Step 2: For each cluster, calculate min riders and solve VRP
        for (Map.Entry<Integer, List<Order>> entry : clusters.entrySet()) {
            int clusterId = entry.getKey();
            List<Order> clusterOrders = entry.getValue();
            if (clusterOrders.isEmpty())
                continue;

            // Find min riders for this cluster
            int minRiders = MinimumRiderToFullFillTheRequest(clusterOrders, riderCapacityKg);
            if (minRiders == -1) {
                throw new Exception("We Can Not Cluster The Order Maybe Any Configuration is Missing");
            }
            Warehouse DepotWareHouse = findWareHouseOfCity(clusterOrders, city);
            // Run VRP solver for this cluster
            VRPCapacitySolver solver = new VRPCapacitySolver(
                    distanceMatrix,
                    clusterOrders,
                    riderCapacityKg,
                    minRiders,
                    0);

            Map<Integer, List<Integer>> routes = solver.solve();

            // Save routes under this cluster
            clusterRoutes.put(clusterId, routes);
        }
        return clusterRoutes;
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
        List<Warehouse> wareHousePerCity = warehouseRepository.findByCity(city);
        if (wareHousePerCity.size() == 0) {
            throw new IllegalArgumentException("No WareHouse For That City");
        } else if (wareHousePerCity.size() == 1)
            return wareHousePerCity.get(0);
        else {
            return findAverageNearestWareHouse(clusterOrders, wareHousePerCity);
        }
    }

    private Warehouse findAverageNearestWareHouse(List<Order> clusterOrders, List<Warehouse> wareHousePerCity) {
        // 1. Find average latitude and longitude of all orders in cluster
        double avgLat = clusterOrders.stream()
                .mapToDouble(Order::getOriginLat)
                .average()
                .orElseThrow(() -> new IllegalArgumentException("No orders in cluster"));

        double avgLng = clusterOrders.stream()
                .mapToDouble(Order::getOriginLng)
                .average()
                .orElseThrow(() -> new IllegalArgumentException("No orders in cluster"));

        // 2. Find nearest warehouse to the average location
        Warehouse nearestWarehouse = null;
        double minDistance = Double.MAX_VALUE;

        for (Warehouse wh : wareHousePerCity) {
            double distance = GeoUtils.distanceKm(avgLat, avgLng, wh.getLat(), wh.getLng());
            if (distance < minDistance) {
                minDistance = distance;
                nearestWarehouse = wh;
            }
        }

        return nearestWarehouse;
    }
}

// niuhiyhiuyiy78y78ihiyiyy87y687

//khiuhi jgyuiuyfr jguytgkuyuf yuty7uy78