package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Capacity-constrained VRP solver.
 *
 * Algorithm:
 *  1. Greedy nearest-neighbour construction (one route per vehicle).
 *  2. 2-opt improvement on each route — eliminates crossings and reduces total distance.
 *     Runs up to MAX_2OPT_ITERATIONS passes or until no improvement is found.
 */
public class VRPCapacitySolver {

    private static final int MAX_2OPT_ITERATIONS = 100;

    private final double[][] distanceMatrix;
    private final List<Order> orders;
    private final int vehicleCapacity;
    private final int vehicleCount;
    private final int depotIndex;

    public VRPCapacitySolver(double[][] distanceMatrix,
                              List<Order> orders,
                              int vehicleCapacity,
                              int vehicleCount,
                              int depotIndex) {
        this.distanceMatrix = distanceMatrix;
        this.orders = orders;
        this.vehicleCapacity = vehicleCapacity;
        this.vehicleCount = vehicleCount;
        this.depotIndex = depotIndex;
    }

    public Map<Integer, List<Order>> solve() {
        Map<Integer, List<Order>> routes = new HashMap<>();
        boolean[] visited = new boolean[orders.size()];

        for (int v = 0; v < vehicleCount; v++) {
            List<Order> route = buildGreedyRoute(visited);
            if (!route.isEmpty()) {
                route = twoOpt(route);
            }
            routes.put(v, route);
        }
        return routes;
    }

    // ---------------------------------------------------------------
    // GREEDY CONSTRUCTION
    // ---------------------------------------------------------------

    private List<Order> buildGreedyRoute(boolean[] visited) {
        List<Order> route = new ArrayList<>();
        double remainingCapacity = vehicleCapacity;

        int start = findBestStart(visited, remainingCapacity);
        if (start == -1) return route;

        route.add(orders.get(start));
        visited[start] = true;
        remainingCapacity -= orders.get(start).getWeightKg();

        int current = start;
        while (true) {
            int next = findNearestFeasible(current, visited, remainingCapacity);
            if (next == -1) break;
            route.add(orders.get(next));
            visited[next] = true;
            remainingCapacity -= orders.get(next).getWeightKg();
            current = next;
        }
        return route;
    }

    private int findBestStart(boolean[] visited, double cap) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < orders.size(); i++) {
            if (!visited[i] && orders.get(i).getWeightKg() <= cap) {
                double d = dist(depotIndex, i);
                if (d < bestDist) { bestDist = d; best = i; }
            }
        }
        return best;
    }

    private int findNearestFeasible(int current, boolean[] visited, double cap) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < orders.size(); i++) {
            if (!visited[i] && orders.get(i).getWeightKg() <= cap) {
                double d = dist(current, i);
                if (d < bestDist) { bestDist = d; best = i; }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------
    // 2-OPT IMPROVEMENT
    // ---------------------------------------------------------------

    /**
     * Standard 2-opt: reverse sub-segments when doing so reduces total distance.
     * Works on the order-index list; the depot is implicit start/end.
     */
    private List<Order> twoOpt(List<Order> route) {
        if (route.size() < 4) return route;

        // Work with indices into the orders list for O(1) distance lookups
        int n = route.size();
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = orders.indexOf(route.get(i));
        }

        boolean improved = true;
        int iteration = 0;
        while (improved && iteration++ < MAX_2OPT_ITERATIONS) {
            improved = false;
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 2; j < n; j++) {
                    // Cost before: edge(i → i+1) + edge(j → j+1)
                    // Cost after:  edge(i → j)   + edge(i+1 → j+1)
                    int next_i = (i + 1) % n;
                    int next_j = (j + 1) % n;
                    double before = dist(idx[i], idx[next_i]) + dist(idx[j], idx[next_j]);
                    double after  = dist(idx[i], idx[j])      + dist(idx[next_i], idx[next_j]);
                    if (after < before - 1e-6) {
                        reverse(idx, i + 1, j);
                        improved = true;
                    }
                }
            }
        }

        // Rebuild route from improved index array
        List<Order> improved_route = new ArrayList<>(n);
        for (int i : idx) improved_route.add(orders.get(i));
        return improved_route;
    }

    private void reverse(int[] arr, int from, int to) {
        while (from < to) {
            int tmp = arr[from]; arr[from] = arr[to]; arr[to] = tmp;
            from++; to--;
        }
    }

    // ---------------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------------

    /** Safe matrix lookup — returns 0 for diagonal, infinity if out of bounds. */
    private double dist(int i, int j) {
        if (i == j) return 0;
        if (i < 0 || j < 0 || i >= distanceMatrix.length || j >= distanceMatrix[i].length) {
            return Double.MAX_VALUE / 2;
        }
        return distanceMatrix[i][j];
    }
}
