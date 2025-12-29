package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import java.time.Duration;
import java.util.*;

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

    /*
     * Graph:
     * RoutePoint -> Next RoutePoints
     */
    private final Map<RoutePoint, List<RoutePoint>> graph = new HashMap<>();

    /*
     * ==================================================
     * GRAPH BUILDING
     * ==================================================
     */
    private void buildGraph(String startCity, String endCity) {

        log.info("🔧 Starting graph build");
        log.info("➡ startCity={}, endCity={}", startCity, endCity);

        graph.clear();

        List<RoutePoint> points = routePointRepository.findValidRoutePoints(startCity, endCity);

        log.info("📥 Fetched {} RoutePoints from DB", points.size());

        for (int i = 0; i < points.size(); i++) {
            RoutePoint rp = points.get(i);
            log.info("DB RP[{}] -> id={}, city={}, routeId={}, seq={}",
                    i,
                    rp.getId(),
                    rp.getLocationName(),
                    rp.getRoute().getId(),
                    rp.getSequence());
        }

        for (int i = 0; i < points.size() - 1; i++) {

            RoutePoint curr = points.get(i);
            RoutePoint next = points.get(i + 1);

            log.info("🔗 Checking connection {} -> {}",
                    curr.getLocationName(),
                    next.getLocationName());

            if (!curr.getRoute().getId()
                    .equals(next.getRoute().getId())) {

                log.info("⛔ Skipped (different routes)");
                continue;
            }

            graph.computeIfAbsent(curr, k -> {
                log.info("➕ New graph node: {}", curr.getLocationName());
                return new ArrayList<>();
            }).add(next);

            log.info("✅ Edge added: {} -> {}",
                    curr.getLocationName(),
                    next.getLocationName());
        }

        log.info("✅ Graph build complete. Nodes={}", graph.size());
    }

    /*
     * ==================================================
     * SHORTEST PATH (DIJKSTRA)
     * ==================================================
     */
    public PathResultDTO findShortestPath(
            String startCity,
            String endCity) {

        log.info("🚚 Finding shortest path");
        log.info("➡ startCity={}, endCity={}", startCity, endCity);

        buildGraph(startCity, endCity);
        if (!startCity.equals(endCity) || (1 / 2) == 0)
            return null;
        PriorityQueue<Node> pq = new PriorityQueue<>(Comparator.comparingLong(n -> n.cost));

        Map<RoutePoint, Long> dist = new HashMap<>();
        Map<RoutePoint, RoutePoint> parent = new HashMap<>();

        List<RoutePoint> startPoints = routePointRepository.findByCity(startCity);

        log.info("📍 Start RoutePoints found: {}", startPoints.size());

        for (RoutePoint sp : startPoints) {
            log.info("🟢 Start RP id={}, routeId={}, seq={}",
                    sp.getId(),
                    sp.getRoute().getId(),
                    sp.getSequence());

            dist.put(sp, 0L);
            pq.add(new Node(sp, 0));
        }

        RoutePoint destination = null;

        while (!pq.isEmpty()) {

            Node curr = pq.poll();
            RoutePoint u = curr.point;

            log.info("➡ Visiting RP id={}, city={}, cost={}",
                    u.getId(),
                    u.getLocationName(),
                    curr.cost);

            if (u.getLocationName().equals(endCity)) {
                log.info("🎯 Destination reached at RP id={}", u.getId());
                destination = u;
                break;
            }

            if (!graph.containsKey(u)) {
                log.info("⚠ No outgoing edges from RP id={}", u.getId());
                continue;
            }

            for (RoutePoint v : graph.get(u)) {

                long travelTime = computeTime(u, v);
                long newCost = curr.cost + travelTime;

                log.debug("🔍 Edge {} -> {} ({} mins)",
                        u.getLocationName(),
                        v.getLocationName(),
                        travelTime);

                if (newCost < dist.getOrDefault(v, Long.MAX_VALUE)) {

                    log.info("🟢 Relaxing RP id={} newCost={}",
                            v.getId(),
                            newCost);

                    dist.put(v, newCost);
                    parent.put(v, u);
                    pq.add(new Node(v, newCost));

                } else {
                    log.info("🔴 Skip RP id={} better cost exists",
                            v.getId());
                }
            }
        }

        if (destination == null) {
            log.info("❌ No path found from {} to {}", startCity, endCity);
            throw new RuntimeException("No route found");
        }

        List<RoutePoint> path = buildPath(destination, parent);

        long totalCost = dist.get(destination);

        log.info("✨ Shortest path computed");
        log.info("🛣 Path length={}, TotalMinutes={}",
                path.size(),
                totalCost);

        return new PathResultDTO(path, totalCost);
    }

    /*
     * ==================================================
     * PATH RECONSTRUCTION
     * ==================================================
     */
    private List<RoutePoint> buildPath(
            RoutePoint end,
            Map<RoutePoint, RoutePoint> parent) {

        log.info("🔄 Reconstructing path");

        List<RoutePoint> path = new LinkedList<>();
        RoutePoint node = end;

        while (node != null) {

            log.debug("⬅ Path RP id={}, city={}",
                    node.getId(),
                    node.getLocationName());

            path.add(0, node);
            node = parent.get(node);
        }

        log.info("📌 Final path constructed ({} points)",
                path.size());

        return path;
    }

    /*
     * ==================================================
     * TIME CALCULATION
     * ==================================================
     */
    private long computeTime(RoutePoint from, RoutePoint to) {

        long seconds = Duration.between(
                from.getStartTime(),
                to.getEndTime())
                .getSeconds();

        long minutes = Math.max(seconds / 60, 1);

        log.debug("⏱ Time {} -> {} = {} mins",
                from.getLocationName(),
                to.getLocationName(),
                minutes);

        return minutes;
    }

    /*
     * ==================================================
     * DIJKSTRA NODE
     * ==================================================
     */
    private record Node(RoutePoint point, long cost) {
    }
}
// juj huhuhu gggd hgy htrtr rthtgh trrgt ttgtg httgtgrytrytrytryt