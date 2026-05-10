package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * High-quality VRP solver backed by Google OR-Tools Routing Library.
 *
 * Used for clusters with more than {@code OR_TOOLS_THRESHOLD} stops where
 * the greedy nearest-neighbour + 2-opt solver produces noticeably suboptimal routes.
 *
 * Strategy: PATH_CHEAPEST_ARC seed + Guided Local Search metaheuristic,
 * capped at {@code TIME_LIMIT_SECONDS} seconds so it never blocks the VRP pipeline.
 *
 * Thread-safety: each solve() call creates independent OR-Tools objects — safe to
 * call from concurrent @Async threads.
 */
public class OrToolsVrpSolver {

    private static final Logger log = LoggerFactory.getLogger(OrToolsVrpSolver.class);
    private static final int TIME_LIMIT_SECONDS = 30;

    /**
     * Whether native libraries loaded successfully at JVM startup.
     * If false, solve() falls back to an empty-route map and the caller
     * should retry with VRPCapacitySolver.
     */
    private static final boolean NATIVES_AVAILABLE;

    static {
        boolean ok = false;
        try {
            Loader.loadNativeLibraries();
            ok = true;
            log.info("OR-Tools native libraries loaded successfully");
        } catch (Throwable t) {
            log.warn("OR-Tools native libraries could not be loaded — large-cluster routing" +
                     " will fall back to greedy+2-opt. Reason: {}", t.getMessage());
        }
        NATIVES_AVAILABLE = ok;
    }

    private final double[][] distanceMatrix; // km, n×n where n = orders.size()
    private final List<Order> orders;
    private final int vehicleCapacityKg;
    private final int vehicleCount;
    private final int depotIndex;

    public OrToolsVrpSolver(double[][] distanceMatrix,
                             List<Order> orders,
                             int vehicleCapacityKg,
                             int vehicleCount,
                             int depotIndex) {
        this.distanceMatrix   = distanceMatrix;
        this.orders           = orders;
        this.vehicleCapacityKg = vehicleCapacityKg;
        this.vehicleCount     = vehicleCount;
        this.depotIndex       = depotIndex;
    }

    /**
     * Runs OR-Tools VRP and returns routes indexed by vehicle slot.
     * Returns empty routes (not null) on failure so the orchestration layer can
     * detect an unserved cluster and react accordingly.
     */
    public Map<Integer, List<Order>> solve() {
        if (!NATIVES_AVAILABLE) {
            log.warn("OR-Tools unavailable — returning empty routes");
            return emptyRoutes();
        }

        int n = orders.size();
        log.info("OR-Tools VRP: {} orders, {} vehicles, depot={}, time={}s",
                 n, vehicleCount, depotIndex, TIME_LIMIT_SECONDS);

        // ── Index manager: n nodes, vehicleCount vehicles, single depot ───────
        RoutingIndexManager manager = new RoutingIndexManager(n, vehicleCount, depotIndex);
        RoutingModel routing        = new RoutingModel(manager);

        // ── Distance callback (km → metres, stored as long) ───────────────────
        final int transitCb = routing.registerTransitCallback(
                (long fromIdx, long toIdx) -> {
                    int from = manager.indexToNode(fromIdx);
                    int to   = manager.indexToNode(toIdx);
                    return distMeters(from, to);
                });
        routing.setArcCostEvaluatorOfAllVehicles(transitCb);

        // ── Capacity dimension ────────────────────────────────────────────────
        // Depot demand is 0; each order node carries its weight in kg (rounded).
        long[] demands = new long[n];
        for (int i = 0; i < n; i++) {
            demands[i] = (i == depotIndex) ? 0L : Math.round(orders.get(i).getWeightKg());
        }
        final int demandCb = routing.registerUnaryTransitCallback(
                (long fromIdx) -> demands[manager.indexToNode(fromIdx)]);

        long[] vehicleCapacities = new long[vehicleCount];
        Arrays.fill(vehicleCapacities, vehicleCapacityKg);
        routing.addDimensionWithVehicleCapacity(
                demandCb,
                0L,                // no slack — load cannot exceed capacity
                vehicleCapacities,
                true,              // start cumulative at zero for every vehicle
                "Capacity");

        // ── Search parameters ─────────────────────────────────────────────────
        RoutingSearchParameters params = main.defaultRoutingSearchParameters()
                .toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(com.google.protobuf.Duration.newBuilder()
                        .setSeconds(TIME_LIMIT_SECONDS).build())
                .build();

        // ── Solve ─────────────────────────────────────────────────────────────
        Assignment solution = routing.solveWithParameters(params);

        if (solution == null) {
            log.warn("OR-Tools found no feasible solution within {}s — returning empty routes",
                     TIME_LIMIT_SECONDS);
            return emptyRoutes();
        }

        Map<Integer, List<Order>> routes = extractRoutes(manager, routing, solution);

        log.info("OR-Tools VRP solved: {} vehicles used, objective={}m",
                 routes.values().stream().filter(r -> !r.isEmpty()).count(),
                 solution.objectiveValue());

        return routes;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<Integer, List<Order>> extractRoutes(RoutingIndexManager manager,
                                                    RoutingModel routing,
                                                    Assignment solution) {
        Map<Integer, List<Order>> routes = new HashMap<>();

        for (int v = 0; v < vehicleCount; v++) {
            List<Order> route = new ArrayList<>();
            long index = routing.start(v);

            while (!routing.isEnd(index)) {
                int node = manager.indexToNode(index);
                if (node != depotIndex) {
                    route.add(orders.get(node));
                }
                index = solution.value(routing.nextVar(index));
            }

            routes.put(v, route);
            if (!route.isEmpty()) {
                log.debug("OR-Tools vehicle {}: {} stops", v, route.size());
            }
        }
        return routes;
    }

    private Map<Integer, List<Order>> emptyRoutes() {
        Map<Integer, List<Order>> routes = new HashMap<>();
        for (int v = 0; v < vehicleCount; v++) routes.put(v, new ArrayList<>());
        return routes;
    }

    /**
     * Converts km distance matrix entry to metres as a long.
     * OR-Tools optimises integer arc costs — metre precision is sufficient.
     */
    private long distMeters(int from, int to) {
        if (from == to) return 0L;
        if (from < 0 || to < 0
                || from >= distanceMatrix.length
                || to   >= distanceMatrix[from].length) {
            return Long.MAX_VALUE / 2;
        }
        return Math.round(distanceMatrix[from][to] * 1_000.0);
    }
}
