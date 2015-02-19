package com.droidmapper.util;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.location.Location;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Html;
import android.util.Log;

import com.droidmapper.CameraActivity;
import com.droidmapper.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Vector;

/**
 * This is a utility class that provides a way to its clients to buffer taken photos in a queue, and
 * to save them on local storage one by one.<br>
 * <b>Note:</b> Currently this thread, after receiving stop command, stops immediately dropping all
 * remaining queued tasks. If this is unwanted, because the app might/will lose a few photos, this
 * class should be modified to first finish queued tasks and then exit.
 */
public class PhotoProcessorThread extends Thread {

    private static final String TAG = PhotoProcessorThread.class.getName();

    private volatile Camera.Parameters camParams;
    private volatile boolean halt;

    private final SimpleDateFormat dateFormat, exifGpsDateFormat, exifDateFormat;
    private final DropboxUploaderThread dbUpldrThread;
    private final ContentResolver contentResolver;
    private final String imgDescriptionTxt;
    private final Vector<JobStruct> queue;
    private final CameraActivity activity;
    private final File mediaStorageDir;
    private final Object lock;

    /**
     * Default constructor. It creates an instance of this class using the Context supplied as
     * parameter.
     *
     * @param activity      The CameraActivity this class is running in, through which it can
     *                      access the current theme, resources, etc.
     * @param dbUpldrThread The Dropbox uploader thread that should upload to Dropbox all the photos
     *                      saved by this thread.
     */
    public PhotoProcessorThread(CameraActivity activity, DropboxUploaderThread dbUpldrThread) {
        if (activity == null) {
            throw new NullPointerException("Activity param can't be null.");
        }
        if (dbUpldrThread == null) {
            throw new NullPointerException("DropboxUploaderThread param can't be null.");
        }
        this.activity = activity;
        this.dbUpldrThread = dbUpldrThread;

        // Create queue that will buffer taken photos:
        queue = new Vector<JobStruct>();

        // Create this thread's lock(used for synchronization):
        lock = new Object();

        // A flag that we use to signal this thread to stop itself:
        halt = false;

        // ContentResolver used to add the captured photos to the device gallery:
        contentResolver = activity.getContentResolver();

        // Description for photos taken by this app, used in device's gallery app:
        imgDescriptionTxt = activity.getString(R.string.ppThread_photo_description);

        // Create(if it does not exist) and initialize the directory in which the images will be saved:
        File picsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        mediaStorageDir = new File(picsDir, activity.getString(R.string.app_name));
        if (!mediaStorageDir.exists()) {
            mediaStorageDir.mkdirs();
        }

        // Create a date format using which we will format photos timestamps and create their file
        // names:
        dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS");
        // And date formats for exif tags:
        exifGpsDateFormat = new SimpleDateFormat("yyyy:MM:dd", Locale.ENGLISH);
        exifDateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
    }

    /**
     * In a background thread write the queued photos on local storage.
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
                // Get new photo to save:
                JobStruct job = queue.remove(0);
                byte[] data = job.data;

                // Create its file:
                String tsText = dateFormat.format(new Date(job.timestamp));
                String filename = tsText + ".jpg";
                String filePath = mediaStorageDir.getPath() + File.separator + filename;
                File photoFile = new File(filePath);

                // If needed fix the photo rotation:
                BitmapFactory.Options bfOptions = new BitmapFactory.Options();
                bfOptions.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bfOptions);
                if ((job.devOrienAtCapture == 0 || job.devOrienAtCapture == 180) && bfOptions.outWidth > bfOptions.outHeight) {
                    Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length);
                    Matrix matrix = new Matrix();
                    matrix.postRotate(90);
                    Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    out.compress(Bitmap.CompressFormat.JPEG, 100, baos);
                    data = baos.toByteArray();
                    try {
                        baos.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                // Write photo data to the created file:
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(photoFile);
                    fos.write(data);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
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

                // Add EXIF data to the captured photo:
                Date date = new Date();
                try {
                    ExifInterface exif = new ExifInterface(filePath);
                    if (job.deviceLocation != null) {
                        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, GpsUtil.convert(job.deviceLocation.getLatitude()));
                        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, GpsUtil.latitudeRef(job.deviceLocation.getLatitude()));
                        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, GpsUtil.convert(job.deviceLocation.getLongitude()));
                        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, GpsUtil.longitudeRef(job.deviceLocation.getLongitude()));
                        double alt = job.deviceLocation.getAltitude();
                        if (alt >= 0) {
                            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, String.valueOf(0));
                        } else {
                            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, String.valueOf(1));
                        }
                        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, String.valueOf(Math.round(alt)));
                        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, exifGpsDateFormat.format(date));
                        exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, job.deviceLocation.getProvider());
                    }

                    exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, String.valueOf(bfOptions.outWidth));
                    exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, String.valueOf(bfOptions.outHeight));
                    exif.setAttribute(ExifInterface.TAG_DATETIME, exifDateFormat.format(date));
                    exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
                    exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);

//                    if(job.devOrienAtCapture == 0 || job.devOrienAtCapture == 360){
//                        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
//                    } else if(job.devOrienAtCapture == 90){
//                        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
//                    } else if(job.devOrienAtCapture == 180){
//                        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_180));
//                    } else if(job.devOrienAtCapture == 270){
//                        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_270));
//                    }

                    if (camParams != null) {
                        String fm = camParams.getFlashMode();
                        if (fm == null || fm.equals(Camera.Parameters.FLASH_MODE_OFF)) {
                            exif.setAttribute(ExifInterface.TAG_FLASH, String.valueOf(0));
                        } else {
                            exif.setAttribute(ExifInterface.TAG_FLASH, String.valueOf(0));
                        }

                        float fl = camParams.getFocalLength();
                        exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, String.valueOf(fl));

                        String wb = camParams.getWhiteBalance();
                        if (wb != null) {
                            if (wb.equals(Camera.Parameters.WHITE_BALANCE_AUTO)) {
                                exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, String.valueOf(ExifInterface.WHITEBALANCE_AUTO));
                            } else {
                                exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, String.valueOf(ExifInterface.WHITEBALANCE_MANUAL));
                            }
                        }

                        String ap = camParams.get("aperture");
                        if (ap != null) {
                            exif.setAttribute(ExifInterface.TAG_APERTURE, ap);
                        }
                    }

                    // TAG_EXPOSURE_TIME TAG_ISO

                    exif.saveAttributes();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Add the saved photo to the device gallery:
                try {
                    MediaStore.Images.Media.insertImage(contentResolver, filePath, filename, imgDescriptionTxt);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }

                // Upload the saved photo to Dropbox:
                dbUpldrThread.queuePhoto(filePath);

                activity.postLastCapturedPhotoFilenameUpdate(filename);
            }
        }
        Log.d(TAG, "run() :: Stop");
    }

    /**
     * Add a new photo to the queue to be saved on local storage.
     *
     * @param data              Byte array containing the image data.
     * @param timestamp         System time at which the image was taken.
     * @param devOrienAtCapture Device orientation at the time the photo is taken.
     * @param deviceLocation    Location at which the photo was captured.
     */
    public void queuePhoto(byte[] data, long timestamp, int devOrienAtCapture, Location deviceLocation) {
        Log.d(TAG, "queuePhoto() :: Already in queue " + queue.size());

        // Don't let the queue have more than 3 photos to prevent out of memory exceptions:
        if (queue.size() > 2) {
            queue.remove(0);
        }

        // Add the new photo to the queue:
        queue.add(new JobStruct(data, timestamp, devOrienAtCapture, deviceLocation));

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

    public void setCamParams(Camera.Parameters camParams) {
        this.camParams = camParams;
    }

    /**
     * A helper class used to hold photo timestamp and image data.
     */
    private class JobStruct {

        private final Location deviceLocation;
        private final int devOrienAtCapture;
        private final long timestamp;
        private final byte[] data;

        /**
         * Default constructor. Creates a new instance of this struct using the given parameters.
         *
         * @param data              Byte array containing the image data.
         * @param timestamp         System time at which the image was taken.
         * @param devOrienAtCapture Device orientation at the time the photo is taken.
         * @param deviceLocation    Location at which the photo was captured
         */
        public JobStruct(byte[] data, long timestamp, int devOrienAtCapture, Location deviceLocation) {
            this.deviceLocation = deviceLocation;
            this.data = data;
            this.timestamp = timestamp;
            this.devOrienAtCapture = devOrienAtCapture;
        }
    }
}