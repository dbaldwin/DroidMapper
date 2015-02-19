package com.droidmapper.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.exception.DropboxException;
import com.droidmapper.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.Vector;

/**
 * This is a utility class that provides a way to its clients to buffer taken photos in a queue, and
 * to upload them to Dropbox one by one.<br>
 * <b>Note:</b> Currently this thread, after receiving stop command, stops immediately dropping all
 * remaining queued tasks. If this is unwanted, because the app might/will lose a few photos, this
 * class should be modified to first finish queued tasks and then exit.
 */
public class DropboxUploaderThread extends Thread {

    private static final String TAG = DropboxUploaderThread.class.getName();

    private volatile boolean halt;

    private final DropboxAPI<AndroidAuthSession> dropboxApi;
    private final Vector<String> queue;
    private final float photoScale;
    private final File tempDir;
    private final Object lock;

    /**
     * Default constructor. It creates an instance of this class using the photo scale supplied as
     * parameter.
     *
     * @param photoScale A float value between 0 and 1, that represents to how much of the original
     *                   size the photo should be scaled.
     * @param dropboxApi A pointer to the DropboxAPI instance that should be used to upload photos
     *                   to Dropbox.
     */
    public DropboxUploaderThread(float photoScale, DropboxAPI<AndroidAuthSession> dropboxApi) {
        if (dropboxApi == null) {
            throw new NullPointerException("DropboxAPI param can't be null.");
        }
        if (photoScale <= 0F || photoScale > 1F) {
            throw new IllegalArgumentException("Param photoScale can't be equal or less than 0(zero).");
        }
        this.photoScale = photoScale;
        this.dropboxApi = dropboxApi;

        // Create queue that will buffer taken photos:
        queue = new Vector<String>();

        // Create this thread's lock(used for synchronization):
        lock = new Object();

        // A flag that we use to signal this thread to stop itself:
        halt = false;

        // Construct path to a temp file:
        File picsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        tempDir = new File(picsDir, "temp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
    }

    /**
     * In a background thread scale the queued photos and upload them to the Dropbox.
     */
    @Override
    public void run() {
        Log.d(TAG, "run() :: Start");
        while (!halt) {
            if (queue.isEmpty()) {
                // If there are no images in the queue, sleep:
                synchronized (lock) {
                    if (!halt) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                        }
                    }
                }
            } else {
                // Get new photo to upload:
                String job = queue.remove(0);
                Log.d(TAG, "run() :: Scaling to " + photoScale + " and uploading " + job);

                try {
                    if (photoScale == 1F) {
                        // The photo shouldn't be scaled, we will upload it in full resolution:
                        uploadFile(job);
                    } else {
                        // Scale the photo first:
                        BitmapFactory.Options bfOptions = new BitmapFactory.Options();
                        bfOptions.inSampleSize = (int) (1F / photoScale); // Note that  decoder uses a final value based on powers of 2, any other value will be rounded down to the nearest power of 2.
                        Bitmap scaled = BitmapFactory.decodeFile(job, bfOptions);

                        // Save the scaled photo to a temp file:
                        File jobFile = new File(job);
                        File tempFile = new File(tempDir, jobFile.getName());
                        if (!tempFile.exists()) {
                            try {
                                tempFile.createNewFile();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        FileOutputStream fos = null;
                        try {
                            fos = new FileOutputStream(tempFile);
                            scaled.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                        } catch (IOException e) {
                            e.printStackTrace();
                        } finally {
                            if (fos != null) {
                                try {
                                    fos.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }

                        // Copy-paste exif tags:
                        copyExifTagsHelper(job, tempFile.getPath(), scaled.getWidth(), scaled.getHeight());

                        // Then upload it:
                        uploadFile(tempFile.getPath());

                        // And delete the temp file:
                        tempFile.delete();
                    }
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (DropboxException e) {
                    e.printStackTrace();
                }

                /*
                 * Note that currently the application doesn't care if upload succeeds of fails.
                 * I reckon that one frame more or less is not a problem. In case that it is an
                 * imperative for all captured frames to be uploaded to the Dropbox this method
                 * should be modified to repeat retry failed uploads. In this case it would also be
                 * a good idea to move this thread into a Service.
                 */
            }
        }
        Log.d(TAG, "run() :: Stop");
    }

    /**
     * Add a new photo to the queue to be uploaded to Dropbox..
     *
     * @param path Path to the photo on local storage.
     */
    public void queuePhoto(String path) {
        Log.d(TAG, "queuePhoto() :: Already in queue " + queue.size());

        // Add the new photo to the queue:
        queue.add(path);

        // Notify the thread about this(it might be sleeping):
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * Stop this thread.
     */
    public void halt() {
        Log.d(TAG, "halt()");

        // Set the stop flag:
        halt = true;

        // Notify the thread about this(it might be sleeping):
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * A helper method that copies exif tag from the source image to the dest image.
     *
     * @param pathSource path to source image.
     * @param pathDest   path to destination image.
     */
    private void copyExifTagsHelper(String pathSource, String pathDest, int destWidth, int destHeight) {
        Log.d(TAG, "copyExifTagsHelper() :: pathSource = " + pathSource);
        Log.d(TAG, "copyExifTagsHelper() :: pathDest = " + pathDest);
        Log.d(TAG, "copyExifTagsHelper() :: destWidth = " + destWidth);
        Log.d(TAG, "copyExifTagsHelper() :: destHeight = " + destHeight);
        try {
            ExifInterface exifSrc = new ExifInterface(pathSource);
            ExifInterface exifDest = new ExifInterface(pathDest);

            String val = exifSrc.getAttribute(ExifInterface.TAG_APERTURE);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_APERTURE, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_DATETIME);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_DATETIME, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_FLASH);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_FLASH, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_ALTITUDE);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_DATESTAMP);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_GPS_LATITUDE, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_LONGITUDE);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_ISO);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_ISO, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_MAKE);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_MAKE, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_MODEL);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_MODEL, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_ORIENTATION);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_ORIENTATION, val);
            }
            val = exifSrc.getAttribute(ExifInterface.TAG_WHITE_BALANCE);
            if(val != null){
                exifDest.setAttribute(ExifInterface.TAG_WHITE_BALANCE, val);
            }
            exifDest.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, String.valueOf(destHeight));
            exifDest.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, String.valueOf(destWidth));

            exifDest.saveAttributes();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * A helper method that uploads a file whose path is passed as parameter to Dropbox.
     *
     * @param path Path to the file that should be uploaded to Dropbox.
     * @throws FileNotFoundException If {@code file} does not exist.
     * @throws DropboxException      If a Dropbox related exception occurs.
     */
    private void uploadFile(String path) throws FileNotFoundException, DropboxException {
        File file = new File(path);
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            DropboxAPI.Entry response = dropboxApi.putFile('/' + file.getName(), inputStream, file.length(), null, null);
            Log.i(TAG, "uploadFile() :: The uploaded file's rev is: " + response.rev);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}