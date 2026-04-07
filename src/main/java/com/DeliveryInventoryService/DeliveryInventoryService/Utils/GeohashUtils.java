package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure Java Geohash encoder/decoder.
 * Precision 6 → ~1.2km × 0.6km cell, good for 2km² coverage.
 */
public class GeohashUtils {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final int[] BITS = { 16, 8, 4, 2, 1 };

    // ---------------------------------------------------------------
    // ENCODE lat/lng → geohash string
    // ---------------------------------------------------------------
    public static String encode(double lat, double lng, int precision) {
        double[] latRange = { -90.0, 90.0 };
        double[] lngRange = { -180.0, 180.0 };

        StringBuilder hash = new StringBuilder();
        boolean isEven = true;
        int bit = 0, ch = 0;

        while (hash.length() < precision) {
            double mid;
            if (isEven) {
                mid = (lngRange[0] + lngRange[1]) / 2;
                if (lng > mid) {
                    ch |= BITS[bit];
                    lngRange[0] = mid;
                } else {
                    lngRange[1] = mid;
                }
            } else {
                mid = (latRange[0] + latRange[1]) / 2;
                if (lat > mid) {
                    ch |= BITS[bit];
                    latRange[0] = mid;
                } else {
                    latRange[1] = mid;
                }
            }
            isEven = !isEven;

            if (bit < 4) {
                bit++;
            } else {
                hash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return hash.toString();
    }

    public static String encode(double lat, double lng) {
        return encode(lat, lng, 6); // default precision 6 ≈ 1.2km × 0.6km
    }

    // ---------------------------------------------------------------
    // DECODE geohash → [lat, lng] center
    // ---------------------------------------------------------------
    public static double[] decode(String geohash) {
        double[] latRange = { -90.0, 90.0 };
        double[] lngRange = { -180.0, 180.0 };
        boolean isEven = true;

        for (char c : geohash.toCharArray()) {
            int cd = BASE32.indexOf(c);
            for (int mask : BITS) {
                if (isEven) {
                    if ((cd & mask) != 0)
                        lngRange[0] = (lngRange[0] + lngRange[1]) / 2;
                    else
                        lngRange[1] = (lngRange[0] + lngRange[1]) / 2;
                } else {
                    if ((cd & mask) != 0)
                        latRange[0] = (latRange[0] + latRange[1]) / 2;
                    else
                        latRange[1] = (latRange[0] + latRange[1]) / 2;
                }
                isEven = !isEven;
            }
        }
        double lat = (latRange[0] + latRange[1]) / 2;
        double lng = (lngRange[0] + lngRange[1]) / 2;
        return new double[] { lat, lng };
    }

    // ---------------------------------------------------------------
    // BOUNDING BOX for a geohash
    // ---------------------------------------------------------------
    public static double[] decodeBbox(String geohash) {
        double[] latRange = { -90.0, 90.0 };
        double[] lngRange = { -180.0, 180.0 };
        boolean isEven = true;

        for (char c : geohash.toCharArray()) {
            int cd = BASE32.indexOf(c);
            for (int mask : BITS) {
                if (isEven) {
                    double mid = (lngRange[0] + lngRange[1]) / 2;
                    if ((cd & mask) != 0)
                        lngRange[0] = mid;
                    else
                        lngRange[1] = mid;
                } else {
                    double mid = (latRange[0] + latRange[1]) / 2;
                    if ((cd & mask) != 0)
                        latRange[0] = mid;
                    else
                        latRange[1] = mid;
                }
                isEven = !isEven;
            }
        }
        // [minLat, minLng, maxLat, maxLng]
        return new double[] { latRange[0], lngRange[0], latRange[1], lngRange[1] };
    }

    // ---------------------------------------------------------------
    // GENERATE all geohash cells covering a bounding box
    // Used to index an entire city polygon
    // ---------------------------------------------------------------
    public static List<String> coverBoundingBox(double minLat, double minLng,
            double maxLat, double maxLng,
            int precision) {
        List<String> cells = new ArrayList<>();

        // Approximate step size at precision 6: ~0.0055 lat × ~0.011 lng
        double latStep = latStepForPrecision(precision);
        double lngStep = lngStepForPrecision(precision);

        double lat = minLat;
        while (lat <= maxLat) {
            double lng = minLng;
            while (lng <= maxLng) {
                cells.add(encode(lat, lng, precision));
                lng += lngStep;
            }
            lat += latStep;
        }
        // deduplicate (grid may produce duplicates near edges)
        return cells.stream().distinct().toList();
    }

    // ---------------------------------------------------------------
    // NEIGHBOURS — useful for fuzzy lookup
    // ---------------------------------------------------------------
    public static List<String> neighbours(String geohash) {
        double[] bbox = decodeBbox(geohash);
        double latH = (bbox[2] - bbox[0]) / 2;
        double lngH = (bbox[3] - bbox[1]) / 2;
        double lat = (bbox[0] + bbox[2]) / 2;
        double lng = (bbox[1] + bbox[3]) / 2;
        int prec = geohash.length();

        List<String> result = new ArrayList<>();
        result.add(encode(lat + latH * 2, lng, prec)); // N
        result.add(encode(lat - latH * 2, lng, prec)); // S
        result.add(encode(lat, lng + lngH * 2, prec)); // E
        result.add(encode(lat, lng - lngH * 2, prec)); // W
        result.add(encode(lat + latH * 2, lng + lngH * 2, prec)); // NE
        result.add(encode(lat + latH * 2, lng - lngH * 2, prec)); // NW
        result.add(encode(lat - latH * 2, lng + lngH * 2, prec)); // SE
        result.add(encode(lat - latH * 2, lng - lngH * 2, prec)); // SW
        return result;
    }

    // ---------------------------------------------------------------
    // STEP SIZES per precision level
    // ---------------------------------------------------------------
    private static double latStepForPrecision(int precision) {
        // Each precision bit halves the range; 5 bits per char
        // lat bits = ceil(5*precision/2)
        int latBits = (5 * precision) / 2;
        return 180.0 / Math.pow(2, latBits);
    }

    private static double lngStepForPrecision(int precision) {
        int lngBits = (5 * precision + 1) / 2;
        return 360.0 / Math.pow(2, lngBits);
    }
}