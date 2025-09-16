package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;

public class GeoUtils {

    // Haversine formula
    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371; // km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public static boolean isWithinRadius(Warehouse warehouse, double lat, double lng, double radiusKm) {
        double dist = distanceKm(warehouse.getLat(), warehouse.getLng(), lat, lng);
        return dist <= radiusKm;
    }
}
