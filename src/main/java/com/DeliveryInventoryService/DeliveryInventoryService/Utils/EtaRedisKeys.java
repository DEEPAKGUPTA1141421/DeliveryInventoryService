package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

public final class EtaRedisKeys {

    private EtaRedisKeys() {
    }

    /** eta:seller:{geohash} */
    public static String sellerKey(String geohash) {
        return "eta:seller:" + geohash;
    }

    /** eta:user:{geohash} */
    public static String userKey(String geohash) {
        return "eta:user:" + geohash;
    }

    /** eta:city:{originCity}:{destCity} — inter-city route time */
    public static String cityRouteKey(String origin, String destination) {
        return "eta:city:" + origin.toLowerCase() + ":" + destination.toLowerCase();
    }

    /** Prefix used for scanning / bulk delete */
    public static final String SELLER_PREFIX = "eta:seller:*";
    public static final String USER_PREFIX = "eta:user:*";
    public static final String CITY_PREFIX = "eta:city:*";
}