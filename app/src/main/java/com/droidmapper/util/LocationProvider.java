package com.droidmapper.util;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

/**
 * Provides a way for the application to get the current device location. The location is retrieved
 * from GPS, NETWORK and PASIVE providers.
 */
public class LocationProvider {

    private static final String TAG = LocationProvider.class.getName();

    // Constants that define frequency of location updates:
    private static final long UPDATE_LOCATION_TIME = 1000L * 60L * 10L;
    private static final long UPDATE_LOCATION_DISTANCE = 100L;
    private static final long TWO_MINUTES = 1000L * 60L * 2L;

    private ArrayList<OnLocationUpdateListener> locationUpdateListeners;
    private Location gpsLocation, networkLocation, passiveLocation;
    private boolean isCreated, isGpsAvailable, isNetworkAvailable;
    private LocationManager locationManager;

    /**
     * Default constructor. Constructs a new instance of this class from the supplied parameter.
     *
     * @param context This application's context.
     */
    public LocationProvider(Context context) {
        if (context == null) {
            throw new NullPointerException("Context param can't be null.");
        }
        isGpsAvailable = true;
        isNetworkAvailable = true;

        // Initialize the array that will hold location update listeners:
        locationUpdateListeners = new ArrayList<OnLocationUpdateListener>(1);

        // We need a location manager instance to access location providers:
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        // Get the last know locations from all three providers:
        gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        passiveLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
    }

    /**
     * Registers an OnLocationUpdateListener instance with this instance of LocationProvider.
     *
     * @param oluListener The listener that wants to listen for location updates.
     */
    public void addOnLocationUpdateListener(OnLocationUpdateListener oluListener) {
        locationUpdateListeners.add(oluListener);
    }

    /**
     * Unregisters an OnLocationUpdateListener instance from this instance of LocationProvider.
     *
     * @param oluListener The listener that no longer wants to listen for location updates.
     */
    public void removeOnLocationUpdateListener(OnLocationUpdateListener oluListener) {
        locationUpdateListeners.remove(oluListener);
    }

    /**
     * Compares the locations acquired from all three providers and based on the location accuracy
     * and timeliness returns the best fix.
     *
     * @return The most accurate and fresh location acquired from any of the three providers.
     */
    public Location getBestLocation() {
        Location ret = null;
        if (isBetterLocation(gpsLocation, networkLocation)) {
            if (isBetterLocation(gpsLocation, passiveLocation)) {
                if (gpsLocation != null) {
                    ret = gpsLocation;
                }
            } else {
                if (passiveLocation != null) {
                    ret = passiveLocation;
                }
            }
        } else {
            if (isBetterLocation(networkLocation, passiveLocation)) {
                if (networkLocation != null) {
                    ret = networkLocation;
                }
            } else {
                if (passiveLocation != null) {
                    ret = passiveLocation;
                }
            }
        }
        return ret;
    }

    /**
     * @return <b>true</b> if at least one of the location providers(GPS, Network) is enabled and
     * not out of service.
     */
    public boolean areProvidersAvailable() {
        boolean ret = false;
        List<String> allProviders = locationManager.getAllProviders();
        if (allProviders.indexOf(LocationManager.GPS_PROVIDER) >= 0
                && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                && isGpsAvailable) {
            ret = true;
        } else if (allProviders.indexOf(LocationManager.NETWORK_PROVIDER) >= 0
                && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                && isNetworkAvailable) {
            ret = true;
        }
        return ret;
    }

    /**
     * Clients need to call this method to send the command to the instance of this class to start
     * listening for location updates.
     */
    public void create() {
        if (isCreated) {
            return;
        }
        isCreated = true;
        isGpsAvailable = true;
        isNetworkAvailable = true;

        // Get the list of all location providers available on local device:
        List<String> allProviders = locationManager.getAllProviders();

        // Start listening for location updates on available and enabled providers:
        if (allProviders.indexOf(LocationManager.NETWORK_PROVIDER) >= 0
                && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, UPDATE_LOCATION_TIME,
                    UPDATE_LOCATION_DISTANCE, locationListener);
        }
        if (allProviders.indexOf(LocationManager.GPS_PROVIDER) >= 0
                && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_LOCATION_TIME,
                    UPDATE_LOCATION_DISTANCE, locationListener);
        }
        if (allProviders.indexOf(LocationManager.PASSIVE_PROVIDER) >= 0) {
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0L, locationListener);
        }
    }

    /**
     * Clients need to call this method to send the command to the instance of this class to stop
     * listening for location updates.<br>
     * This must be done to preserve memory and lower battery consumption.
     */
    public void destroy() {
        this.isCreated = false;
        locationManager.removeUpdates(locationListener);
        locationUpdateListeners.clear();
    }

    /**
     * Determines whether one Location reading is better than the current Location fix.
     *
     * @param location            The new Location that you want to evaluate.
     * @param currentBestLocation The current Location fix, to which you want to compare the new one.
     * @return <b>true</b> if new location is better then the current location, otherwise <b>false</b>.
     */
    private boolean isBetterLocation(Location location, Location currentBestLocation) {
        // A new location is always better than no location:
        if (currentBestLocation == null) {
            return true;
        }

        if (location == null) {
            return false;
        }

        // Check whether the new location fix is newer or older:
        long timeDelta = location.getTime() - currentBestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
        boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
        boolean isNewer = timeDelta > 0;

        if (isSignificantlyNewer) {
            // If it's been more than two minutes since the current location, use the new location
            // because the user has likely moved:
            return true;
        } else if (isSignificantlyOlder) {
            // If the new location is more than two minutes older, it must be worse:
            return false;
        }

        // Check whether the new location fix is more or less accurate:
        int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        // Check if the old and new location are from the same provider:
        boolean isFromSameProvider = isSameProvider(location.getProvider(), currentBestLocation.getProvider());

        // Determine location quality using a combination of timeliness and accuracy:
        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            return true;
        }
        return false;
    }

    /**
     * Checks whether providers supplied as parameters are the same.
     *
     * @param provider1 First provider.
     * @param provider2 Second provider.
     * @return <b>true</b> if they are the same, otherwise <b>false</b>.
     */
    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
            return provider2 == null;
        }
        return provider1.equals(provider2);
    }

    /**
     * Notifies all registered listeners device location has been updated.
     */
    private void notifyObservers() {
        Location bestFixLocation = getBestLocation();
        for (OnLocationUpdateListener luListener : locationUpdateListeners) {
            luListener.onLocationUpdate(bestFixLocation);
        }
    }

    /**
     * An instance of LocationListener that we use to listen for location updates.
     */
    private final LocationListener locationListener = new LocationListener() {

        /**
         * Called when the location has changed.
         *
         * @param location The new location.
         */
        public void onLocationChanged(Location location) {
            // Based on the location's provider updated the last known location for that provider,
            // if the new location is a better fix:
            String provider = location.getProvider();
            if (provider == LocationManager.GPS_PROVIDER) {
                if (isBetterLocation(location, gpsLocation)) {
                    gpsLocation = location;
                    notifyObservers();
                }
            } else if (provider == LocationManager.NETWORK_PROVIDER) {
                if (isBetterLocation(location, networkLocation)) {
                    networkLocation = location;
                    notifyObservers();
                }
            } else if (provider == LocationManager.PASSIVE_PROVIDER) {
                if (isBetterLocation(location, passiveLocation)) {
                    passiveLocation = location;
                    notifyObservers();
                }
            }
        }

        /**
         * Called when the provider is disabled by the user. If requestLocationUpdates is called on
         * an already disabled provider, this method is called immediately.
         *
         * @param provider The name of the location provider associated with this update.
         */
        public void onProviderDisabled(String provider) {
            // Do nothing.
        }

        /**
         * Called when the provider is enabled by the user.
         *
         * @param provider The name of the location provider associated with this update.
         */
        public void onProviderEnabled(String provider) {
            // Do nothing.
        }

        /**
         * Called when the provider status changes. This method is called when a provider is unable
         * to fetch a location or if the provider has recently become available after a period of
         * unavailability.
         *
         * @param provider The name of the location provider associated with this update.
         * @param status The new status of the provider, as per the constants defined in the
         *                {@link android.location.LocationProvider} class.
         * @param extras An optional Bundle which will contain provider specific status variables.
         */
        public void onStatusChanged(String provider, int status, Bundle extras) {
            if (provider == LocationManager.GPS_PROVIDER) {
                isGpsAvailable = (status == android.location.LocationProvider.AVAILABLE);
            } else if (provider == LocationManager.NETWORK_PROVIDER) {
                isNetworkAvailable = (status == android.location.LocationProvider.AVAILABLE);
            } else if (provider == LocationManager.PASSIVE_PROVIDER) {
                // Do nothing.
            }
        }
    };

    /**
     * This interface should be implemented by all classes that want to listen for device location
     * updates.
     */
    public static interface OnLocationUpdateListener {

        /**
         * A callback method that will be called to notify the listener that device location has
         * changed.
         *
         * @param bestFixLocation The best possible location acquired from any of the registered
         *                        providers.
         */
        public void onLocationUpdate(Location bestFixLocation);
    }
}
