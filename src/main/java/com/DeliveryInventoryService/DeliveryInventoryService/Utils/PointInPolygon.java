package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone.LatLngPoint;

import java.util.List;

public class PointInPolygon {

    /**
     * Ray casting algorithm — returns true if (lat, lng) is inside the polygon.
     * Works for any simple (non-self-intersecting) polygon.
     */
    public static boolean contains(List<LatLngPoint> polygon, double lat, double lng) {
        int n = polygon.size();
        if (n < 3) return false;

        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).getLat(), yi = polygon.get(i).getLng();
            double xj = polygon.get(j).getLat(), yj = polygon.get(j).getLng();

            boolean intersect = ((yi > lng) != (yj > lng))
                    && (lat < (xj - xi) * (lng - yi) / (yj - yi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }
}
