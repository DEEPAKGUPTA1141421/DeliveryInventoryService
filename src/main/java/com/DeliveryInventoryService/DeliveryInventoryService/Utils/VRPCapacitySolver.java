package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;

import java.util.*;

public class VRPCapacitySolver {

    private final double[][] distanceMatrix; // From OSRM/Google
    private final List<Order> orders;
    private final int vehicleCapacity; // in kg
    private final int vehicleCount;
    private final int depotIndex; // warehouse index

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

    /**
     * Core method to solve VRP with capacity constraints
     */
    public Map<Integer, List<Integer>> solve() {
        System.out.println("caling the solve function");
        Map<Integer, List<Integer>> routes = new HashMap<>();
        double[] remainingCapacity = new double[vehicleCount];

        Arrays.fill(remainingCapacity, vehicleCapacity);
        System.out.println("caling the solve function" + vehicleCapacity);
        boolean[] visited = new boolean[orders.size()];

        for (int v = 0; v < vehicleCount; v++) {
            List<Integer> route = new ArrayList<>();
            double cap = vehicleCapacity;

            int current = findBestStart(visited, cap); // choose best start order
            if (current == -1)
                continue;
            System.out.println("Best Start " + current);
            // Start from chosen point
            route.add(current);
            visited[current] = true;
            cap -= orders.get(current).getWeightKg();

            // Visit nearest feasible until no more capacity
            while (true) {
                int next = findNearestFeasible(current, visited, cap);
                System.out.println("findNearestFeasible " + next);
                if (next == -1)
                    break;

                route.add(next);
                visited[next] = true;
                cap -= orders.get(next).getWeightKg();
                current = next;
            }

            // Finally return to depot
            route.add(depotIndex);

            routes.put(v, route);
            remainingCapacity[v] = cap;
        }

        return routes;
    }

    /**
     * Find nearest feasible order for given vehicle
     */
    private int findNearestFeasible(int current, boolean[] visited, double cap) {
        int nextIndex = -1;
        double bestDistance = Double.MAX_VALUE;

        for (int i = 0; i < orders.size(); i++) {
            if (!visited[i] && orders.get(i).getWeightKg() <= cap) {
                double d = distanceMatrix[current][i];
                if (d < bestDistance) {
                    bestDistance = d;
                    nextIndex = i;
                }
            }
        }
        return nextIndex;
    }

    /**
     * Select the best starting point for a rider (closest to depot)
     */
    private int findBestStart(boolean[] visited, double cap) {
        int bestIndex = -1;
        double bestDistance = Double.MAX_VALUE;

        for (int i = 0; i < orders.size(); i++) {
            if (!visited[i] && orders.get(i).getWeightKg() <= cap) {
                double d = distanceMatrix[depotIndex][i];
                if (d < bestDistance) {
                    bestDistance = d;
                    bestIndex = i;
                }
            }
        }
        return bestIndex;
    }
}

// nkhuihiuhuyhuukhuh uiiuhnkhuihuih hiuhijfkjhuihjib juojij