package com.droidmapper.util;

import android.content.Context;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;

/**
 * A class that contains utility methods used by other classes.
 */
public class Util {

    private static final String TAG = Util.class.getName();

    /**
     * Private constructor because we want to prevent other classes from making instances of this
     * class.
     */
    private Util() {

    }

    /**
     * Gets the real path to an image from its Uri. For example, gets "/mnt/sdcard/sample.jpg" from
     * something like "content://media/external/images/media/17".
     *
     * @param context    current application context.
     * @param contentURI Uri to an image file.
     * @return File path to the file whose Uri was supplied.
     */
    public static String getFilePathFromUri(Context context, Uri contentURI) {
        String result;
        Cursor cursor = context.getContentResolver().query(contentURI, null, null, null, null);
        if (cursor == null) { // Source is Dropbox or other similar local file path
            result = contentURI.getPath();
        } else {
            cursor.moveToFirst();
            int idx = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
            result = cursor.getString(idx);
            cursor.close();
        }
        return result;
    }

    /**
     * A helper method that copies exif tag from the source image to the dest image.
     *
     * @param pathSource path to source image.
     * @param pathDest   path to destination image.
     */
    public static void copyExifTags(String pathSource, String pathDest, int destWidth, int destHeight) {
        Log.d(TAG, "copyExifTags() :: pathSource = " + pathSource);
        Log.d(TAG, "copyExifTags() :: pathDest = " + pathDest);
        Log.d(TAG, "copyExifTags() :: destWidth = " + destWidth);
        Log.d(TAG, "copyExifTags() :: destHeight = " + destHeight);
        try {
            ExifInterface exifSrc = new ExifInterface(pathSource);
            ExifInterface exifDest = new ExifInterface(pathDest);

            String val = exifSrc.getAttribute(ExifInterface.TAG_APERTURE);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_APERTURE, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_DATETIME);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_DATETIME, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_FLASH);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_FLASH, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_ALTITUDE);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_DATESTAMP);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_GPS_LATITUDE, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_LONGITUDE);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_ISO);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_ISO, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_MAKE);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_MAKE, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_MODEL);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_MODEL, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_ORIENTATION);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_ORIENTATION, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_WHITE_BALANCE);
            if (val != null) {
                exifDest.setAttribute(ExifInterface.TAG_WHITE_BALANCE, val);
            }
            exifDest.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, String.valueOf(destHeight));
            exifDest.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, String.valueOf(destWidth));

            exifDest.saveAttributes();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}