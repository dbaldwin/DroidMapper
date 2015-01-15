package com.droidmapper.util;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.droidmapper.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
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

    private volatile boolean halt;

    private final DropboxUploaderThread dbUpldrThread;
    private final ContentResolver contentResolver;
    private final SimpleDateFormat dateFormat;
    private final String imgDescriptionTxt;
    private final Vector<JobStruct> queue;
    private final File mediaStorageDir;
    private final Object lock;

    /**
     * Default constructor. It creates an instance of this class using the Context supplied as
     * parameter.
     *
     * @param context       The Context this class is running in, through which it can
     *                      access the current theme, resources, etc.
     * @param dbUpldrThread The Dropbox uploader thread that should upload to Dropbox all the photos
     *                      saved by this thread.
     */
    public PhotoProcessorThread(Context context, DropboxUploaderThread dbUpldrThread) {
        if (context == null) {
            throw new NullPointerException("Context param can't be null.");
        }
        if (dbUpldrThread == null) {
            throw new NullPointerException("DropboxUploaderThread param can't be null.");
        }
        this.dbUpldrThread = dbUpldrThread;

        // Create queue that will buffer taken photos:
        queue = new Vector<JobStruct>();

        // Create this thread's lock(used for synchronization):
        lock = new Object();

        // A flag that we use to signal this thread to stop itself:
        halt = false;

        // ContentResolver used to add the captured photos to the device gallery:
        contentResolver = context.getContentResolver();

        // Description for photos taken by this app, used in device's gallery app:
        imgDescriptionTxt = context.getString(R.string.ppThread_photo_description);

        // Create(if it does not exist) and initialize the directory in which the images will be saved:
        File picsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        mediaStorageDir = new File(picsDir, context.getString(R.string.app_name));
        if (!mediaStorageDir.exists()) {
            mediaStorageDir.mkdirs();
        }

        // Create a date format using which we will format photos timestamps and create their file
        // names:
        dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS");
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

                // Add the saved photo to the device gallery:
                try {
                    MediaStore.Images.Media.insertImage(contentResolver, filePath, filename, imgDescriptionTxt);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }

                // Upload the saved photo to Dropbox:
                dbUpldrThread.queuePhoto(filePath);
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
     */
    public void queuePhoto(byte[] data, long timestamp, int devOrienAtCapture) {
        Log.d(TAG, "queuePhoto() :: Already in queue " + queue.size());

        // Don't let the queue have more than 3 photos to prevent out of memory exceptions:
        if (queue.size() > 2) {
            queue.remove(0);
        }

        // Add the new photo to the queue:
        queue.add(new JobStruct(data, timestamp, devOrienAtCapture));

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
     * A helper class used to hold photo timestamp and image data.
     */
    private class JobStruct {

        private final int devOrienAtCapture;
        private final long timestamp;
        private final byte[] data;

        /**
         * Default constructor. Creates a new instance of this struct using the given parameters.
         *
         * @param data              Byte array containing the image data.
         * @param timestamp         System time at which the image was taken.
         * @param devOrienAtCapture Device orientation at the time the photo is taken.
         */
        public JobStruct(byte[] data, long timestamp, int devOrienAtCapture) {
            this.data = data;
            this.timestamp = timestamp;
            this.devOrienAtCapture = devOrienAtCapture;
        }
    }
}
