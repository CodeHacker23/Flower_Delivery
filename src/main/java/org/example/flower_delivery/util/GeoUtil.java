package org.example.flower_delivery.util;

import java.math.BigDecimal;

/**
 * Утилиты для работы с геокоординатами (расстояние Haversine).
 */
public final class GeoUtil {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoUtil() {
    }

    /**
     * Расстояние между двумя точками по формуле Haversine (в километрах).
     *
     * @param lat1 широта точки 1 (градусы)
     * @param lon1 долгота точки 1 (градусы)
     * @param lat2 широта точки 2
     * @param lon2 долгота точки 2
     * @return расстояние в км
     */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    /**
     * Перегрузка для BigDecimal (координаты из БД).
     */
    public static double distanceKm(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return Double.POSITIVE_INFINITY;
        }
        return distanceKm(lat1.doubleValue(), lon1.doubleValue(), lat2.doubleValue(), lon2.doubleValue());
    }

    /**
     * Проверка: точка (lat, lon) в радиусе radiusKm от (refLat, refLon)?
     */
    public static boolean isWithinRadiusKm(double lat, double lon, double refLat, double refLon, double radiusKm) {
        return distanceKm(lat, lon, refLat, refLon) <= radiusKm;
    }

    /** Радиус 200 м = 0.2 км (для проверки «курьер в магазине / у получателя»). */
    public static final double RADIUS_200_M_KM = 0.2;
}
