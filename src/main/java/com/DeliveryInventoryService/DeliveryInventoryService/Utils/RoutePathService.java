package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.PathResultDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RoutePoint;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RoutePointRepository;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
class Edge {
    private String fromCity;
    private String toCity;
    private long travelMinutes;
}

@Service
@RequiredArgsConstructor
public class RoutePathService {

    private static final Logger log = LoggerFactory.getLogger(RoutePathService.class);

    private final RoutePointRepository routePointRepository;

    private final Map<String, List<Edge>> graph = new HashMap<>();

    public void buildGraph(String startCity, String endCity) {

        log.info("🔍 Fetching valid route points for startCity={} endCity={}", startCity, endCity);

        List<RoutePoint> points = routePointRepository.findValidRoutePoints(startCity, endCity);

        log.info("📌 Total RoutePoints fetched: {}", points.size());
        log.info("📌 Building graph edges...");
        for (int i = 0; i < points.size(); i++) {
            log.info("RoutePoint {}: city={} routeId={} sequence={}",
                    i, points.get(i).getLocationName(),
                    points.get(i).getRoute().getId(),
                    points.get(i).getSequence());
        }
        for (int i = 0; i < points.size() - 1; i++) {

            RoutePoint current = points.get(i);
            RoutePoint next = points.get(i + 1);

            // only connect within the same route
            if (!current.getRoute().getId().equals(next.getRoute().getId())) {
                log.info("Skipping non-connected points: {} -> {}",
                        current.getLocationName(), next.getLocationName());
                continue;
            }

            String from = current.getLocationName();
            String to = next.getLocationName();

            long minutes = computeTime(current, next);

            log.info("Adding edge {} -> {} ({} min)", from, to, minutes);

            Edge edge = new Edge(from, to, minutes);

            graph.computeIfAbsent(from, k -> new ArrayList<>()).add(edge);
        }

        log.info("✅ Graph build completed. Total cities mapped: {}", graph.size());
    }

    private long computeTime(RoutePoint p1, RoutePoint p2) {
        long seconds = Duration.between(p1.getStartTime(), p2.getEndTime()).getSeconds();
        long mins = Math.max(seconds / 60, 1);

        log.debug("Computed travel time from {} -> {}: {} mins",
                p1.getCity(), p2.getCity(), mins);

        return mins;
    }

    public PathResultDTO findShortestPath(String startCity, String endCity) {

        log.info("🚚 Finding shortest path from {} to {}", startCity, endCity);

        buildGraph(startCity, endCity);

        PriorityQueue<Node> pq = new PriorityQueue<>(Comparator.comparingLong(Node::getCost));
        Map<String, Long> dist = new HashMap<>();
        Map<String, String> parent = new HashMap<>();

        log.info("Initializing Dijkstra starting node={} cost=0", startCity);

        dist.put(startCity, 0L);
        pq.add(new Node(startCity, 0));

        while (!pq.isEmpty()) {

            Node curr = pq.poll();
            log.info("➡ Visiting city={} with cost={}", curr.getCity(), curr.getCost());

            if (curr.getCity().equals(endCity)) {
                log.info("🎯 Reached destination: {}", endCity);
                break;
            }

            if (!graph.containsKey(curr.getCity())) {
                log.warn("⚠ No outgoing edges from city={}", curr.getCity());
                continue;
            }

            for (Edge edge : graph.get(curr.getCity())) {

                long newCost = curr.getCost() + edge.getTravelMinutes();
                log.info("Checking edge {} -> {} ({} mins)",
                        edge.getFromCity(), edge.getToCity(), edge.getTravelMinutes());

                if (newCost < dist.getOrDefault(edge.getToCity(), Long.MAX_VALUE)) {

                    log.info("🟢 Updating city={} newCost={}",
                            edge.getToCity(), newCost);

                    dist.put(edge.getToCity(), newCost);
                    parent.put(edge.getToCity(), curr.getCity());
                    pq.add(new Node(edge.getToCity(), newCost));

                } else {
                    log.info("🔴 Skipping city={} as better cost exists", edge.getToCity());
                }
            }
        }

        if (!dist.containsKey(endCity)) {
            log.error("❌ No route found from {} to {}", startCity, endCity);
            throw new RuntimeException("No route found!");
        }

        List<String> finalPath = buildPath(startCity, endCity, parent);
        long totalCost = dist.get(endCity);

        log.info("✨ Shortest path found: {} ({} mins)", finalPath, totalCost);

        return new PathResultDTO(finalPath, totalCost);
    }

    private List<String> buildPath(String start, String end, Map<String, String> parent) {
        log.info("🔄 Reconstructing path result...");
        List<String> path = new LinkedList<>();

        String node = end;
        while (node != null) {
            log.debug("Path step: {}", node);
            path.add(0, node);
            node = parent.get(node);
        }

        log.info("📌 Final computed path: {}", path);
        return path;
    }

    @Data
    @AllArgsConstructor
    private static class Node {
        private String city;
        private long cost;
    }
}
// nhjhuj hihu huhui hhui ghyuhu hhuhu hyhu