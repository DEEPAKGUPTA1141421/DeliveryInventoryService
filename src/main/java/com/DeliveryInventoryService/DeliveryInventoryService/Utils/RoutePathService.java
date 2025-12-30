package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.PathResultDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RoutePoint;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RoutePointRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RoutePathService {

    private static final Logger log = LoggerFactory.getLogger(RoutePathService.class);
    private final RoutePointRepository routePointRepository;

    // --- CACHES ---
    // City -> List of Points (For Transfers)
    private Map<String, List<RoutePoint>> cityGraph = new HashMap<>();

    // CurrentPointID -> NextPoint (For Travel within same route)
    // We use a Map instead of List.indexOf to avoid Lombok/Equals issues entirely
    private Map<UUID, RoutePoint> nextHopMap = new HashMap<>();

    /**
     * Pre-loads all data and maps "Next Steps" explicitly using UUIDs.
     */
    private void buildInMemoryGraph() {
        List<RoutePoint> allPoints = routePointRepository.findAll();

        // 1. Group by City
        cityGraph = allPoints.stream()
                .collect(Collectors.groupingBy(RoutePoint::getLocationName));

        // 2. Group by Route, Sort, and Link the chain
        Map<UUID, List<RoutePoint>> pointsByRoute = allPoints.stream()
                .collect(Collectors.groupingBy(rp -> rp.getRoute().getId()));

        nextHopMap.clear();

        for (List<RoutePoint> routePoints : pointsByRoute.values()) {
            // Sort by sequence (1, 2, 3...)
            routePoints.sort(Comparator.comparingInt(RoutePoint::getSequence));

            // Map: Point A -> Point B, Point B -> Point C
            for (int i = 0; i < routePoints.size() - 1; i++) {
                RoutePoint current = routePoints.get(i);
                RoutePoint next = routePoints.get(i + 1);
                nextHopMap.put(current.getId(), next);
            }
        }

        log.info("Graph Built: {} Cities, {} Hops mapped.", cityGraph.size(), nextHopMap.size());
    }

    public PathResultDTO findShortestPath(String startCity, String endCity) {
        // Always rebuild graph to get fresh data
        buildInMemoryGraph();

        // Priority Queue: Orders nodes by Earliest Arrival Time
        PriorityQueue<NodeState> pq = new PriorityQueue<>(Comparator.comparing(n -> n.arrivalTime));

        // Tracks best arrival time at every specific RoutePoint ID
        Map<UUID, LocalDateTime> bestArrivalAtPoint = new HashMap<>();

        // Tracks path for reconstruction: Child UUID -> Parent UUID
        Map<UUID, UUID> parentMap = new HashMap<>();

        // Lookup map for ID -> Object (needed for reconstruction)
        Map<UUID, RoutePoint> idToPointMap = cityGraph.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(RoutePoint::getId, rp -> rp));

        Set<UUID> visited = new HashSet<>();

        // --- STEP 1: INITIALIZE START NODES ---
        List<RoutePoint> startPoints = cityGraph.getOrDefault(startCity, Collections.emptyList());

        if (startPoints.isEmpty()) {
            throw new RuntimeException("Start city '" + startCity + "' not found in database.");
        }

        for (RoutePoint startPoint : startPoints) {
            // We are "ready to leave" the start point at its EndTime
            bestArrivalAtPoint.put(startPoint.getId(), startPoint.getEndTime());
            pq.add(new NodeState(startPoint, startPoint.getEndTime()));
        }

        RoutePoint finalDestinationPoint = null;
        LocalDateTime bestFinalTime = LocalDateTime.MAX;

        // --- STEP 2: DIJKSTRA ---
        while (!pq.isEmpty()) {
            NodeState current = pq.poll();
            RoutePoint u = current.point;

            if (visited.contains(u.getId()))
                continue;
            visited.add(u.getId());

            // Check Destination
            if (u.getLocationName().equals(endCity)) {
                if (current.arrivalTime.isBefore(bestFinalTime)) {
                    bestFinalTime = current.arrivalTime;
                    finalDestinationPoint = u;
                }
                // Continue to ensure we find the absolute optimal time across all route options
                continue;
            }

            // --- OPTION A: TRAVEL (Next stop on same route) ---
            // Using our safe Map, not indexOf
            RoutePoint nextInRoute = nextHopMap.get(u.getId());

            if (nextInRoute != null) {
                // Cost is the arrival time at the NEXT stop
                relax(u, nextInRoute, nextInRoute.getEndTime(), pq, bestArrivalAtPoint, parentMap);
            }

            // --- OPTION B: TRANSFER (Switch vehicle at same city) ---
            List<RoutePoint> potentialTransfers = cityGraph.getOrDefault(u.getLocationName(), Collections.emptyList());

            for (RoutePoint transferPoint : potentialTransfers) {
                // Constraint 1: Must be a different route
                // Constraint 2: Transfer vehicle must depart AFTER we arrived
                boolean isDifferentRoute = !transferPoint.getRoute().getId().equals(u.getRoute().getId());
                boolean isTimeValid = !transferPoint.getStartTime().isBefore(u.getEndTime());

                if (isDifferentRoute && isTimeValid) {
                    relax(u, transferPoint, transferPoint.getEndTime(), pq, bestArrivalAtPoint, parentMap);
                }
            }
        }

        if (finalDestinationPoint == null) {
            throw new RuntimeException("No route found between " + startCity + " and " + endCity);
        }

        List<RoutePoint> path = reconstructPath(finalDestinationPoint, parentMap, idToPointMap);
        return new PathResultDTO(path, bestFinalTime);
    }

    private void relax(RoutePoint u, RoutePoint v, LocalDateTime arrivalTime,
            PriorityQueue<NodeState> pq,
            Map<UUID, LocalDateTime> bestArrivalAtPoint,
            Map<UUID, UUID> parentMap) {

        if (arrivalTime.isBefore(bestArrivalAtPoint.getOrDefault(v.getId(), LocalDateTime.MAX))) {
            bestArrivalAtPoint.put(v.getId(), arrivalTime);
            parentMap.put(v.getId(), u.getId());
            pq.add(new NodeState(v, arrivalTime));
        }
    }

    private List<RoutePoint> reconstructPath(RoutePoint endPoint,
            Map<UUID, UUID> parentMap,
            Map<UUID, RoutePoint> idToPointMap) {
        LinkedList<RoutePoint> path = new LinkedList<>();
        RoutePoint curr = endPoint;

        while (curr != null) {
            path.addFirst(curr);
            UUID parentId = parentMap.get(curr.getId());
            curr = parentId != null ? idToPointMap.get(parentId) : null;
        }
        return path;
    }

    private record NodeState(RoutePoint point, LocalDateTime arrivalTime) {
    }
}

// hujo hkujioij hjib hjjio jiikoikioij