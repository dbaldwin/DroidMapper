package com.droidmapper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.location.Location;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.session.AppKeyPair;
import com.droidmapper.util.Constants;
import com.droidmapper.util.DropboxUploaderThread;
import com.droidmapper.util.LocationProvider;
import com.droidmapper.util.PhotoProcessorThread;
import com.droidmapper.view.CameraView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * This activity creates the camera screen GUI on the device's screen and handles user input. Its
 * purpose is to take photos with back camera in the specified interval and to send taken photos
 * to the PhotoProcessorThread for further processing.
 */
public class CameraActivity extends Activity {

    // Constants used as keys for the extras passed to this activity:
    public static final String EXTRA_DB_OAUTH2_ACCESS_TOKEN = CameraActivity.class.getName() + "EXTRA_DB_OAUTH2_ACCESS_TOKEN";
    public static final String EXTRA_INTERVAL = CameraActivity.class.getName() + "EXTRA_INTERVAL";
    public static final String EXTRA_DELAY = CameraActivity.class.getName() + "EXTRA_DELAY";
    public static final String EXTRA_SIZE = CameraActivity.class.getName() + "EXTRA_SIZE";

    private static final String TAG = CameraActivity.class.getName();

    // Views:
    private CameraView cameraView;
    private Button buttonStop;

    // Dropbox API:
    private DropboxAPI<AndroidAuthSession> dropboxApi;
    private String dbOauth2AccessToekn;

    // Util threads:
    private PhotoProcessorThread photoProcsThread;
    private DropboxUploaderThread dbUpldrThread;

    // Other:
    private OrientationEventListener orientationListener;
    private volatile long takePicInvocTimestamp;
    private LocationProvider locationProvider;
    private int devOrien, devOrienAtCapture;
    private SimpleDateFormat dateFormat;
    private File mediaStorageDir;
    private int interval, delay;
    private Handler handler;
    private float size;

    // TODO: Note that currently util threads, after receiving stop command, stop immediately, they do not finish queued tasks.
    // TODO: If this is unwanted, because the app might/will lose a few photos, they should be modified to first finish queued tasks and then exit.

    /**
     * A framework method that is invoked by the system when this activity is first created. It sets
     * up its GUI, retrieves the extras from the Intent that started this activity and initializes
     * the Dropbox API.
     *
     * @param savedInstanceState If the activity is being re-initialized after previously being shut
     *                           down then this Bundle contains the data it most recently supplied
     *                           in onSaveInstanceState(Bundle), otherwise it is <b>null</b>.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get the extras from the intent that started this activity:
        Intent intent = getIntent();
        if (!intent.hasExtra(EXTRA_DB_OAUTH2_ACCESS_TOKEN)) {
            throw new IllegalArgumentException("EXTRA_DB_OAUTH2_ACCESS_TOKEN was not found in the intent that started this activity!");
        } else if (!intent.hasExtra(EXTRA_INTERVAL)) {
            throw new IllegalArgumentException("EXTRA_INTERVAL was not found in the intent that started this activity!");
        } else if (!intent.hasExtra(EXTRA_DELAY)) {
            throw new IllegalArgumentException("EXTRA_DELAY was not found in the intent that started this activity!");
        } else if (!intent.hasExtra(EXTRA_SIZE)) {
            throw new IllegalArgumentException("EXTRA_SIZE was not found in the intent that started this activity!");
        } else {
            dbOauth2AccessToekn = intent.getStringExtra(EXTRA_DB_OAUTH2_ACCESS_TOKEN);
            interval = intent.getIntExtra(EXTRA_INTERVAL, -1);
            delay = intent.getIntExtra(EXTRA_DELAY, -1);
            size = intent.getFloatExtra(EXTRA_SIZE, 0F);
        }

        // Inflates the GUI defined in the XML file:
        setContentView(R.layout.activity_camera);

        // We need to keep the screen on in order for camera to work:
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Get references to views defined in the GUI:
        cameraView = (CameraView) findViewById(R.id.activityCamera_cameraView);
        buttonStop = (Button) findViewById(R.id.activityCamera_buttonStop);

        // Listen for clicks on the stop button:
        buttonStop.setOnClickListener(onClickListener);

        // Initialize the Handler instance. We use it to schedule tasks to run on the GUI thread at
        // some point in future.
        handler = new Handler();

        // Initialize the location provider needed for geo tagging taken photos:
        locationProvider = new LocationProvider(this);
        cameraView.setGeoTaggingLocation(locationProvider.getBestLocation());

        // Initialize the Dropbox API:
        AppKeyPair appKeys = new AppKeyPair(Constants.APP_KEY, Constants.APP_SECRET);
        AndroidAuthSession session = new AndroidAuthSession(appKeys, dbOauth2AccessToekn);
        dropboxApi = new DropboxAPI<AndroidAuthSession>(session);

        // Create(if it does not exist) and initialize the directory in which the images will be saved:
        File picsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        mediaStorageDir = new File(picsDir, getString(R.string.app_name));
        if (!mediaStorageDir.exists()) {
            mediaStorageDir.mkdirs();
        }

        // Create a date format using which we will format photos timestamps and create their file
        // names:
        dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS");

        // We need to listen for device orientation changes in order to know when it is held in
        // portrait and when in landscape so that we could properly rotate the captured photos:
        orientationListener = new OrientationEventListener(this) {

            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation != ORIENTATION_UNKNOWN) {
                    // Clamp the device orientation:
                    int degrees = 0;
                    if (orientation <= 45 || orientation > 315) {
                        degrees = 0;
                    } else if (orientation > 45 && orientation <= 135) {
                        degrees = 90;
                    } else if (orientation > 135 && orientation <= 225) {
                        degrees = 180;
                    } else {
                        degrees = 270;
                    }
                    devOrien = degrees;
                }
            }
        };
    }

    /**
     * Called after onCreate(Bundle) — or after onRestart() when the activity had been stopped,
     * but is now again being displayed to the user. It delays the start of photo capturing by
     * the amount of time specified in the Intent that started this activity.
     */
    @Override
    public void onStart() {
        super.onStart();

        // Start the thread that will upload the saved photos to Dropbox:
        dbUpldrThread = new DropboxUploaderThread(size, dropboxApi);
        dbUpldrThread.start();

        // Start the thread that will save the photo data to external storage:
        photoProcsThread = new PhotoProcessorThread(this, dbUpldrThread);
        photoProcsThread.start();

        // Delay the start of photo taking:
        handler.postDelayed(delayPhotoTakingRunnable, delay);

        // Start listening for rotation changes:
        orientationListener.enable();

        // Start listening for location updates:
        locationProvider.addOnLocationUpdateListener(onLocationUpdateListener);
        locationProvider.create();
    }

    /**
     * Called when you the activity is no longer visible to the user. It cancels the scheduled
     * photo capture.
     */
    @Override
    public void onStop() {
        // User is leaving the screen, cancel the photo taking:
        handler.removeCallbacks(delayPhotoTakingRunnable);

        // Stop the photo processor thread:
        if (photoProcsThread != null) {
            photoProcsThread.halt();
            photoProcsThread = null;
        }

        // Stop the Dropbox uploader thread:
        if (dbUpldrThread != null) {
            dbUpldrThread.halt();
            dbUpldrThread = null;
        }

        // Stop listening for rotation changes:
        orientationListener.disable();

        // Stop listening for location updates:
        locationProvider.removeOnLocationUpdateListener(onLocationUpdateListener);
        locationProvider.destroy();

        super.onStop();
    }

    /**
     * An instance of the OnClickListener interface that provides a callback method to the start
     * Button view in this activity's GUI, so that it can inform the activity when the user has
     * clicked on it.
     */
    private View.OnClickListener onClickListener = new View.OnClickListener() {

        /**
         * Called when a view has been clicked.
         *
         * @param view The view that was clicked.
         */
        @Override
        public void onClick(View view) {
            if (view == buttonStop) {
                // User is leaving the screen, cancel the photo taking:
                handler.removeCallbacks(delayPhotoTakingRunnable);

                // Close the activity:
                finish();
            }
        }
    };

    /**
     * A runnable instance used to schedule photo capturing.
     */
    private Runnable delayPhotoTakingRunnable = new Runnable() {

        /**
         * A callback method which invokes photo capture.
         */
        @Override
        public void run() {
            // If the activity is not being closed, record the current system time and take a
            // picture:
            if (!isFinishing()) {
                takePicInvocTimestamp = SystemClock.elapsedRealtime();
                cameraView.takePicture(pictureCallback);
                devOrienAtCapture = devOrien;
            }
        }
    };

    /**
     * An instance of the PictureCallback interface which callback method is invoked by the underling
     * API to send us the image data of the captured photo.
     */
    private Camera.PictureCallback pictureCallback = new Camera.PictureCallback() {

        /**
         * Called when image data is available after a picture is taken.
         *
         * @param data Byte array of the picture encoded as JPG.
         * @param camera The camera that took the photo.
         */
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            // Log.d(TAG, "pictureCallback.onPictureTaken() :: devOrienAtCapture = " + devOrienAtCapture);
            /*
             * A choice was made here between two options:<br>
             * 1) Write the taken photo to the local storage in the GUI thread, and avoid possible
             * OutOfMemoryErrors but also possibly delay the capture of the next image by a second,<br>
             * 2) Make a copy of the captured image data and send it to a background thread which
             * is supposed to write it to the local storage. But by doing that risk an OutOfMemoryError.<br>
             * <br>
             * The second approach is better if the captured image isn't in "high resolution".
             * Theoretically it would also work for "high resolution" images too but only if the
             * android:largeHeap="true" attribute is set in the <application></application> tag of
             * the AndroidManifest.xml file.<br>
             * Otherwise,the first method is much better because its simpler, less error prone and
             * doesn't use as much memory as the second one.
             */

            // 1) Write the taken photo in the current thread:
            // Create its file:
            String tsText = dateFormat.format(new Date(System.currentTimeMillis()));
            String filename = tsText + ".jpg";
            String filePath = mediaStorageDir.getPath() + File.separator + filename;
            File photoFile = new File(filePath);
            // If needed fix the photo rotation:
            BitmapFactory.Options bfOptions = new BitmapFactory.Options();
            bfOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bfOptions);
            // Log.d(TAG, "pictureCallback.onPictureTaken() :: img wxh = " + bfOptions.outWidth + "x" + bfOptions.outHeight);
            if ((devOrienAtCapture == 0 || devOrienAtCapture == 180) && bfOptions.outWidth > bfOptions.outHeight) {
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
                MediaStore.Images.Media.insertImage(getContentResolver(), filePath, filename, getString(R.string.ppThread_photo_description));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            // Upload the saved photo to Dropbox:
            dbUpldrThread.queuePhoto(filePath);

            // 2) Send the captured photo data to another thread to save it on external storage:
//            byte[] dataCopy = new byte[data.length];
//            System.arraycopy(data, 0, dataCopy, 0, data.length);
//            photoProcsThread.queuePhoto(dataCopy, System.currentTimeMillis(), devOrienAtCapture);

            // Restart camera preview:
            cameraView.restartPreview();

            // If the activity is not being closed, schedule another photo capture:
            if (!isFinishing()) {
                long schdlNextPicIn = (takePicInvocTimestamp + interval) - SystemClock.elapsedRealtime();
                Log.d(TAG, "pictureCallback.onPictureTaken() :: Current time is " + SystemClock.elapsedRealtime() + " schedule pic in " + schdlNextPicIn);
                if (schdlNextPicIn < 1L) {
                    schdlNextPicIn = 1L;
                }
                handler.postDelayed(delayPhotoTakingRunnable, schdlNextPicIn);
            }
        }
    };

    /**
     * An instance of the OnLocationUpdateListener interface which callback method is invoked by the
     * underlying API when a new location fix is acquired.
     */
    private LocationProvider.OnLocationUpdateListener onLocationUpdateListener = new LocationProvider.OnLocationUpdateListener() {

        /**
         * A callback method that will be called to notify the listener that device location has
         * changed.
         *
         * @param bestFixLocation The best possible location acquired from any of the registered
         *                        providers.
         */
        @Override
        public void onLocationUpdate(Location bestFixLocation) {
            // Update the location the camera uses to geo tag images:
            cameraView.setGeoTaggingLocation(bestFixLocation);
        }
    };
}
