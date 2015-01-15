package com.droidmapper.util;

import com.dropbox.client2.session.Session;

/**
 * A utility class that encapsulates the constants used throughout the app, currently those are
 * Dropbox API credentials.
 */
public final class Constants {

    // Type of access this app requests from Dropbox:
    public static final Session.AccessType ACCESS_TYPE = Session.AccessType.APP_FOLDER;

    // Dropbox credentials:
    public static final String APP_SECRET = "iwfdykrdak6qfuy";
    public static final String APP_KEY = "klrvs3j75xo2erh";

    /**
     * Private constructor prevents instantiation of this class.
     */
    private Constants() {

    }
}
