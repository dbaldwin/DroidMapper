package com.droidmapper.util;

/**
 * Utility class used to convert latitude and longitude into DMS (degree minute second) format.
 */
public class GpsUtil {

    /**
     * Returns ref for latitude which is S or N.
     *
     * @param latitude
     * @return S or N
     */
    public static String latitudeRef(double latitude) {
        return latitude < 0.0d ? "S" : "N";
    }

    /**
     * Returns ref for latitude which is S or N.
     *
     * @param longitude
     * @return S or N
     */
    public static String longitudeRef(double longitude) {
        return longitude < 0.0d ? "W" : "E";
    }

    /**
     * Convert latitude into DMS (degree minute second) format. For instance<br/>
     * -79.948862 becomes<br/>
     * 79/1,56/1,55903/1000<br/>
     * It works for latitude and longitude<br/>
     *
     * @param latOrLong
     * @return
     */
    public static String convert(double latOrLong) {
        latOrLong = Math.abs(latOrLong);
        int degree = (int) latOrLong;
        latOrLong *= 60;
        latOrLong -= (degree * 60.0d);
        int minute = (int) latOrLong;
        latOrLong *= 60;
        latOrLong -= (minute * 60.0d);
        int second = (int) (latOrLong * 1000.0d);

        StringBuilder sb = new StringBuilder(20);
        sb.append(degree);
        sb.append("/1,");
        sb.append(minute);
        sb.append("/1,");
        sb.append(second);
        sb.append("/1000,");
        return sb.toString();
    }
}