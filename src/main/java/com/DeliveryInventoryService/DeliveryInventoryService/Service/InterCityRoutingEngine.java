package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.CityRouteEtaEntry;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Route;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RoutePoint;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Vehicle;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RouteRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Multi-hop inter-city routing engine.
 *
 * Builds an in-memory weighted directed graph where:
 * - Nodes = city names
 * - Edges = (fromCity, toCity, travelTimeSeconds, vehicleType, routeId)
 *
 * Each edge represents a consecutive pair of stops on an active Route.
 * Edge weight = road travel time calculated from OSRM distance + vehicle speed.
 *
 * When a vehicle switch occurs at an intermediate hub, TRANSFER_PENALTY_SECONDS
 * is added to the cumulative cost to model loading/unloading time.
 *
 * Dijkstra finds the minimum-time path from origin to destination, potentially
 * hopping through N intermediate cities and switching vehicles.
 *
 * Result:
 * OptimalRouteResult → ordered list of CityHop objects describing each leg,
 * plus total time in seconds.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterCityRoutingEngine {

    private final RouteRepository routeRepository;
    private final WarehouseRepository warehouseRepository;
    private final InterCityRouteService interCityRouteService;

    // ── DTOs ─────────────────────────────────────────────────────────────────

    /** One leg of a multi-hop route */
    public record CityHop(
            String fromCity,
            String toCity,
            long travelTimeSeconds,
            double distanceKm,
            Vehicle.VehicleType vehicleType,
            UUID routeId,
            boolean transferAtArrival // true if next leg uses a different vehicle
    ) {
    }

    /** Full optimal route result */
    public record OptimalRouteResult(
            String originCity,
            String destinationCity,
            List<CityHop> hops,
            List<String> cityPath, // ordered city names including start and end
            long totalTravelTimeSeconds, // sum of leg times + transfer penalties
            long transferPenaltySeconds, // total penalty added
            int vehicleSwitches) {
        /** Human-readable summary */
        public String summary() {
            long hours = totalTravelTimeSeconds / 3600;
            long minutes = (totalTravelTimeSeconds % 3600) / 60;
            return String.format("%s → %s | %dh %dm | %d hop(s) | %d vehicle switch(es)",
                    originCity, destinationCity, hours, minutes,
                    hops.size(), vehicleSwitches);
        }
    }

    // Internal graph edge
    private record GraphEdge(
            String toCity,
            long travelTimeSeconds,
            double distanceKm,
            Vehicle.VehicleType vehicleType,
            UUID routeId) {
    }

    // Dijkstra state node
    private record DijkstraState(
            String city,
            long cumulativeTimeSeconds,
            UUID currentRouteId // null = origin (no vehicle yet)
    ) implements Comparable<DijkstraState> {
        @Override
        public int compareTo(DijkstraState o) {
            return Long.compare(this.cumulativeTimeSeconds, o.cumulativeTimeSeconds);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Find the minimum-time route from originCity to destCity, allowing any
     * number of intermediate stops and vehicle switches.
     *
     * @throws NoSuchElementException if no route exists between the two cities
     */
    public OptimalRouteResult findOptimalRoute(String originCity, String destCity) {
        log.info("Finding optimal route: {} → {}", originCity, destCity);

        Map<String, List<GraphEdge>> graph = buildGraph();

        if (graph.isEmpty()) {
            throw new NoSuchElementException("No active routes found in database");
        }
        if (!graph.containsKey(originCity)) {
            throw new NoSuchElementException("Origin city not found in route graph: " + originCity);
        }

        return dijkstra(graph, originCity, destCity);
    }

    /**
     * Returns all direct one-hop options between two cities, sorted by time.
     * Useful for the frontend to show alternative carrier options.
     */
    public List<CityHop> findDirectHops(String originCity, String destCity) {
        Map<String, List<GraphEdge>> graph = buildGraph();
        List<GraphEdge> edges = graph.getOrDefault(originCity, Collections.emptyList());
        return edges.stream()
                .filter(e -> e.toCity().equalsIgnoreCase(destCity))
                .map(e -> new CityHop(originCity, destCity, e.travelTimeSeconds(),
                        e.distanceKm(), e.vehicleType(), e.routeId(), false))
                .sorted(Comparator.comparingLong(CityHop::travelTimeSeconds))
                .collect(Collectors.toList());
    }

    /**
     * Returns all cities reachable from originCity (direct edges only).
     */
    public List<String> reachableCities(String originCity) {
        Map<String, List<GraphEdge>> graph = buildGraph();
        return graph.getOrDefault(originCity, Collections.emptyList())
                .stream()
                .map(GraphEdge::toCity)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    // ── Graph construction ────────────────────────────────────────────────────

    /**
     * Builds a weighted directed graph from all active routes in the database.
     *
     * For each active Route, consecutive RoutePoint pairs (sorted by sequence)
     * become directed edges. The edge weight is calculated as:
     * distanceMetres from Redis cache / vehicle speed in m/s
     *
     * Falls back to Haversine straight-line distance × 1.3 road-factor when
     * the Redis cache has no entry for that specific pair.
     */
    private Map<String, List<GraphEdge>> buildGraph() {
        List<Route> activeRoutes = routeRepository.findByStatus(Route.Status.ACTIVE);
        Map<String, List<GraphEdge>> graph = new HashMap<>();

        for (Route route : activeRoutes) {
            List<RoutePoint> points = route.getPoints()
                    .stream()
                    .sorted(Comparator.comparingInt(RoutePoint::getSequence))
                    .collect(Collectors.toList());

            Vehicle.VehicleType vType = route.getVehicle() != null && route.getVehicle().getVehicleType() != null
                    ? route.getVehicle().getVehicleType()
                    : Vehicle.VehicleType.FOUR_WHEELER;

            double speedMs = InterCityRouteService.getSpeedMs(vType);

            for (int i = 0; i < points.size() - 1; i++) {
                RoutePoint from = points.get(i);
                RoutePoint to = points.get(i + 1);

                String fromCity = from.getLocationName();
                String toCity = to.getLocationName();

                // Try to get distance from Redis cache for this city pair
                long travelSeconds;
                double distanceKm;

                CityRouteEtaEntry cached = interCityRouteService.getCached(fromCity, toCity);
                if (cached != null) {
                    travelSeconds = cached.getTotalTravelTimeSeconds();
                    distanceKm = cached.getTotalDistanceKm();
                } else {
                    // Fallback: Haversine × 1.3 road-factor
                    distanceKm = haversineKm(from.getLatitude(), from.getLongitude(),
                            to.getLatitude(), to.getLongitude()) * 1.3;
                    travelSeconds = Math.round((distanceKm * 1000) / speedMs);
                }

                GraphEdge edge = new GraphEdge(toCity, travelSeconds, distanceKm, vType, route.getId());
                graph.computeIfAbsent(fromCity, k -> new ArrayList<>()).add(edge);
            }
        }

        log.debug("Graph built: {} cities, {} total edges",
                graph.size(), graph.values().stream().mapToInt(List::size).sum());
        return graph;
    }

    // ── Dijkstra ──────────────────────────────────────────────────────────────

    private OptimalRouteResult dijkstra(
            Map<String, List<GraphEdge>> graph,
            String originCity,
            String destCity) {

        // Best known time to reach each city
        Map<String, Long> bestTime = new HashMap<>();
        // Previous state for path reconstruction: city → (parentCity, edge used)
        Map<String, String> prevCity = new HashMap<>();
        Map<String, GraphEdge> prevEdge = new HashMap<>();

        PriorityQueue<DijkstraState> pq = new PriorityQueue<>();

        // Initialise all cities with MAX
        graph.keySet().forEach(c -> bestTime.put(c, Long.MAX_VALUE));
        bestTime.put(originCity, 0L);
        pq.add(new DijkstraState(originCity, 0L, null));

        while (!pq.isEmpty()) {
            DijkstraState curr = pq.poll();

            if (curr.cumulativeTimeSeconds() > bestTime.getOrDefault(curr.city(), Long.MAX_VALUE)) {
                continue; // stale entry
            }

            if (curr.city().equalsIgnoreCase(destCity))
                break;

            for (GraphEdge edge : graph.getOrDefault(curr.city(), Collections.emptyList())) {

                // Transfer penalty when switching vehicles
                long penalty = 0L;
                if (curr.currentRouteId() != null && !curr.currentRouteId().equals(edge.routeId())) {
                    penalty = InterCityRouteService.TRANSFER_PENALTY_SECONDS;
                }

                long newTime = curr.cumulativeTimeSeconds() + edge.travelTimeSeconds() + penalty;

                if (newTime < bestTime.getOrDefault(edge.toCity(), Long.MAX_VALUE)) {
                    bestTime.put(edge.toCity(), newTime);
                    prevCity.put(edge.toCity(), curr.city());
                    prevEdge.put(edge.toCity(), edge);
                    pq.add(new DijkstraState(edge.toCity(), newTime, edge.routeId()));
                }
            }
        }

        if (!bestTime.containsKey(destCity) || bestTime.get(destCity) == Long.MAX_VALUE) {
            throw new NoSuchElementException(
                    "No route found between " + originCity + " and " + destCity);
        }

        return reconstructResult(originCity, destCity, prevCity, prevEdge,
                bestTime.get(destCity));
    }

    // ── Path reconstruction ───────────────────────────────────────────────────

    private OptimalRouteResult reconstructResult(
            String originCity,
            String destCity,
            Map<String, String> prevCity,
            Map<String, GraphEdge> prevEdge,
            long totalTime) {

        LinkedList<String> cityPath = new LinkedList<>();
        LinkedList<CityHop> hops = new LinkedList<>();
        long transferPenalty = 0L;
        int switches = 0;

        String cur = destCity;
        UUID prevRouteId = null;

        while (cur != null) {
            cityPath.addFirst(cur);
            String parent = prevCity.get(cur);
            if (parent == null)
                break;

            GraphEdge edge = prevEdge.get(cur);

            boolean isTransfer = (prevRouteId != null && !prevRouteId.equals(edge.routeId()));
            if (isTransfer) {
                transferPenalty += InterCityRouteService.TRANSFER_PENALTY_SECONDS;
                switches++;
            }

            hops.addFirst(new CityHop(
                    parent,
                    cur,
                    edge.travelTimeSeconds(),
                    edge.distanceKm(),
                    edge.vehicleType(),
                    edge.routeId(),
                    isTransfer));

            prevRouteId = edge.routeId();
            cur = parent;
        }

        return new OptimalRouteResult(
                originCity,
                destCity,
                new ArrayList<>(hops),
                new ArrayList<>(cityPath),
                totalTime,
                transferPenalty,
                switches);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}