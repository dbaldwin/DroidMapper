package com.droidmapper;

import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.location.Location;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Html;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.session.AppKeyPair;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.droidmapper.util.Constants;
import com.droidmapper.util.DropboxUploaderThread;
import com.droidmapper.util.GpsUtil;
import com.droidmapper.util.PhotoProcessorThread;
import com.droidmapper.util.Util;
import com.droidmapper.view.CameraView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * This activity creates the camera screen GUI on the device's screen and handles user input. Its
 * purpose is to take photos with back camera in the specified interval and to send taken photos
 * to the PhotoProcessorThread for further processing.
 */
public class CameraActivity extends Activity implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    // Constants used as keys for the extras passed to this activity:
    public static final String EXTRA_DB_OAUTH2_ACCESS_TOKEN = CameraActivity.class.getName() + "EXTRA_DB_OAUTH2_ACCESS_TOKEN";
    public static final String EXTRA_INTERVAL_TYPE = CameraActivity.class.getName() + "EXTRA_INTERVAL_TYPE";
    public static final String EXTRA_INTERVAL = CameraActivity.class.getName() + "EXTRA_INTERVAL";
    public static final String EXTRA_DELAY = CameraActivity.class.getName() + "EXTRA_DELAY";
    public static final String EXTRA_SIZE = CameraActivity.class.getName() + "EXTRA_SIZE";

    // Interval type constants:
    public static final int INTERVAL_TYPE_DISTANCE = 1;
    public static final int INTERVAL_TYPE_TIME = 2;

    // Request code to use when launching the Google Play Services API resolution activity:
    private static final int REQUEST_RESOLVE_ERROR = 1001;
    // Unique tag for the Google Play Services API error dialog fragment:
    private static final String DIALOG_ERROR = "dialog_error";
    // Key used to preserve the state of the resolvingError field between activity restarts:
    private static final String STATE_RESOLVING_ERROR = "resolving_error";

    private static final String TAG = CameraActivity.class.getName();

    // Views:
    private TextView textViewLat, textViewLong, textViewAlt, textViewSpd, textViewPhoto;
    private CameraView cameraView;
    private Button buttonStop;

    // Dropbox API:
    private DropboxAPI<AndroidAuthSession> dropboxApi;
    private String dbOauth2AccessToken;

    // Util threads:
    private PhotoProcessorThread photoProcsThread;
    private DropboxUploaderThread dbUpldrThread;

    // Location:
    private GoogleApiClient googleApiClient;
    private boolean resolvingError;
    private Location lastLocation;

    // Other:
    private SimpleDateFormat dateFormat, exifGpsDateFormat, exifDateFormat;
    private OrientationEventListener orientationListener;
    private volatile long takePicInvocTimestamp;
    private int interval, intervalType, delay;
    private int devOrien, devOrienAtCapture;
    private File mediaStorageDir;
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
        } else if (!intent.hasExtra(EXTRA_INTERVAL_TYPE)) {
            throw new IllegalArgumentException("EXTRA_INTERVAL_TYPE was not found in the intent that started this activity!");
        } else if (!intent.hasExtra(EXTRA_INTERVAL)) {
            throw new IllegalArgumentException("EXTRA_INTERVAL was not found in the intent that started this activity!");
        } else if (!intent.hasExtra(EXTRA_DELAY)) {
            throw new IllegalArgumentException("EXTRA_DELAY was not found in the intent that started this activity!");
        } else if (!intent.hasExtra(EXTRA_SIZE)) {
            throw new IllegalArgumentException("EXTRA_SIZE was not found in the intent that started this activity!");
        } else {
            dbOauth2AccessToken = intent.getStringExtra(EXTRA_DB_OAUTH2_ACCESS_TOKEN);
            intervalType = intent.getIntExtra(EXTRA_INTERVAL_TYPE, -1);
            interval = intent.getIntExtra(EXTRA_INTERVAL, -1);
            delay = intent.getIntExtra(EXTRA_DELAY, -1);
            size = intent.getFloatExtra(EXTRA_SIZE, 0F);
        }

        // Inflates the GUI defined in the XML file:
        setContentView(R.layout.activity_camera);

        // We need to keep the screen on in order for camera to work:
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Get references to views defined in the GUI:
        textViewLat = (TextView) findViewById(R.id.activityCamera_textViewLat);
        textViewLong = (TextView) findViewById(R.id.activityCamera_textViewLong);
        textViewAlt = (TextView) findViewById(R.id.activityCamera_textViewAlt);
        textViewSpd = (TextView) findViewById(R.id.activityCamera_textViewSpd);
        textViewPhoto = (TextView) findViewById(R.id.activityCamera_textViewPhoto);
        cameraView = (CameraView) findViewById(R.id.activityCamera_cameraView);
        buttonStop = (Button) findViewById(R.id.activityCamera_buttonStop);

        // Listen for clicks on the stop button:
        buttonStop.setOnClickListener(onClickListener);

        // Set text view default texts:
        textViewLat.setText(Html.fromHtml(getString(R.string.activityCamera_textViewLat, "")));
        textViewLong.setText(Html.fromHtml(getString(R.string.activityCamera_textViewLong, "")));
        textViewAlt.setText(Html.fromHtml(getString(R.string.activityCamera_textViewAlt, "")));
        textViewSpd.setText(Html.fromHtml(getString(R.string.activityCamera_textViewSpd, "")));
        textViewPhoto.setText(Html.fromHtml(getString(R.string.activityCamera_textViewPhoto, "")));

        // Initialize the Handler instance. We use it to schedule tasks to run on the GUI thread at
        // some point in future.
        handler = new Handler();

        // Connect to Google Play Service in order to use Fused Location Provider to geo-tag taken photos:
        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
        cameraView.setGeoTaggingLocation(lastLocation);

        // Restore the state of the resolvingError variable after activity restart:
        if (savedInstanceState != null) {
            resolvingError = savedInstanceState.getBoolean(STATE_RESOLVING_ERROR, false);
        }

        // Initialize the Dropbox API:
        AppKeyPair appKeys = new AppKeyPair(Constants.APP_KEY, Constants.APP_SECRET);
        AndroidAuthSession session = new AndroidAuthSession(appKeys, dbOauth2AccessToken);
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
        // And date formats for exif tags:
        exifGpsDateFormat = new SimpleDateFormat("yyyy:MM:dd", Locale.ENGLISH);
        exifDateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");

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

        if (!resolvingError) {
            googleApiClient.connect();
        }

        // Start the thread that will upload the saved photos to Dropbox:
        dbUpldrThread = new DropboxUploaderThread(size, dropboxApi);
        dbUpldrThread.start();

        // Start the thread that will save the photo data to external storage:
        photoProcsThread = new PhotoProcessorThread(this, dbUpldrThread);
        photoProcsThread.start();

        if (intervalType == INTERVAL_TYPE_TIME) {
            // Delay the start of photo taking:
            handler.postDelayed(delayPhotoTakingRunnable, delay);
        }

        // Start listening for rotation changes:
        orientationListener.enable();
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

        if (googleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, locationListener);
        }
        googleApiClient.disconnect();
        super.onStop();
    }

    /**
     * Called by the system before the activity may be killed so that when it comes back some time
     * in the future it can restore its state.
     *
     * @param outState Bundle in which the state is saved.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_RESOLVING_ERROR, resolvingError);
    }

    /**
     * Called when an activity launched by this activity exits, giving us the requestCode we started
     * it with, the resultCode it returned, and any additional data from it. The resultCode will be
     * RESULT_CANCELED if the activity explicitly returned that, didn't return any result, or
     * crashed during its operation.
     *
     * @param requestCode The integer request code originally supplied to startActivityForResult(),
     *                    allowing us to identify who this result came from.
     * @param resultCode  The integer result code returned by the child activity through its
     *                    setResult().
     * @param data        An Intent, which can return result data to the caller.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_RESOLVE_ERROR) {
            resolvingError = false;
            if (resultCode == RESULT_OK) {
                // Make sure the app is not already connected or attempting to connect:
                if (!googleApiClient.isConnecting() && !googleApiClient.isConnected()) {
                    googleApiClient.connect();
                }
            }
        }
    }

    /**
     * After calling connect() on GoogleApiClient, this method will be invoked asynchronously when
     * the connect request has successfully completed.
     *
     * @param bundle Bundle of data provided to clients by Google Play services. May be null if no
     *               content is provided by the service.
     */
    @Override
    public void onConnected(Bundle bundle) {
        lastLocation = LocationServices.FusedLocationApi.getLastLocation(googleApiClient);
        if (lastLocation != null) {
            cameraView.setGeoTaggingLocation(lastLocation);
        }
        updateShownLocationDataHelper();

        // Request location updates from Google Play Services Fused Provider:
        if (intervalType == INTERVAL_TYPE_DISTANCE) {
            LocationRequest locationRequest = new LocationRequest();
            locationRequest.setInterval(1000L);
            locationRequest.setFastestInterval(1000L);
            locationRequest.setSmallestDisplacement(interval);
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, locationListener);
        } else {
            LocationRequest locationRequest = new LocationRequest();
            locationRequest.setInterval(1000L);
            locationRequest.setFastestInterval(1000L);
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, locationListener);
        }
    }

    /**
     * Called when the Google Play Services client is temporarily in a disconnected state.
     *
     * @param i The reason for the disconnection.
     */
    @Override
    public void onConnectionSuspended(int i) {

    }

    /**
     * Called when there was an error connecting the Google Play Services client to the service.
     *
     * @param connectionResult A ConnectionResult that can be used for resolving the error, and
     *                         deciding what sort of error occurred.
     */
    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        if (resolvingError) {
            // Already attempting to resolve an error.
            return;
        } else if (connectionResult.hasResolution()) {
            try {
                resolvingError = true;
                connectionResult.startResolutionForResult(this, REQUEST_RESOLVE_ERROR);
            } catch (IntentSender.SendIntentException e) {
                // There was an error with the resolution intent. Try again.
                googleApiClient.connect();
            }
        } else {
            // Show dialog using GooglePlayServicesUtil.getErrorDialog():
            // Create a fragment for the error dialog:
            ErrorDialogFragment dialogFragment = new ErrorDialogFragment();
            // Pass the error that should be displayed:
            Bundle args = new Bundle();
            args.putInt(DIALOG_ERROR, connectionResult.getErrorCode());
            dialogFragment.setArguments(args);
            dialogFragment.show(getFragmentManager(), DIALOG_ERROR);
            resolvingError = true;
        }
    }

    /**
     * A helper method that updates the on-screen filename of the last captured image in the GUI thread.
     *
     * @param filename of the last captured image.
     */
    public void postLastCapturedPhotoFilenameUpdate(final String filename) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                textViewPhoto.setText(Html.fromHtml(getString(R.string.activityCamera_textViewPhoto, filename)));
            }
        });
    }

    /**
     * A helper method that updates the on-screen texts with new location data.
     */
    private void updateShownLocationDataHelper() {
        if (lastLocation != null) {
            double lat = lastLocation.getLatitude();
            double lon = lastLocation.getLongitude();
            double alt = lastLocation.getAltitude();
            float spd = lastLocation.getSpeed();

            textViewLat.setText(Html.fromHtml(getString(R.string.activityCamera_textViewLat, String.valueOf(lat))));
            textViewLong.setText(Html.fromHtml(getString(R.string.activityCamera_textViewLong, String.valueOf(lon))));
            textViewAlt.setText(Html.fromHtml(getString(R.string.activityCamera_textViewAlt, String.valueOf(Math.round(alt)))));
            textViewSpd.setText(Html.fromHtml(getString(R.string.activityCamera_textViewSpd, String.valueOf(Math.round(spd)))));
        }
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
            // If needed, fix the photo rotation:
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
            // Add EXIF data to the captured photo:
            Date date = new Date();
            try {
                ExifInterface exif = new ExifInterface(filePath);
                if (lastLocation != null) {
                    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, GpsUtil.convert(lastLocation.getLatitude()));
                    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, GpsUtil.latitudeRef(lastLocation.getLatitude()));
                    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, GpsUtil.convert(lastLocation.getLongitude()));
                    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, GpsUtil.longitudeRef(lastLocation.getLongitude()));
                    double alt = lastLocation.getAltitude();
                    if (alt >= 0) {
                        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, String.valueOf(0));
                    } else {
                        exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, String.valueOf(1));
                    }
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, String.valueOf(Math.round(alt)));
                    exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, exifGpsDateFormat.format(date));
                    exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, lastLocation.getProvider());
                }
                exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, String.valueOf(bfOptions.outWidth));
                exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, String.valueOf(bfOptions.outHeight));
                exif.setAttribute(ExifInterface.TAG_DATETIME, exifDateFormat.format(date));
                exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
                exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);

//                if(devOrienAtCapture == 0 || devOrienAtCapture == 360){
//                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
//                } else if(devOrienAtCapture == 90){
//                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
//                } else if(devOrienAtCapture == 180){
//                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_180));
//                } else if(devOrienAtCapture == 270){
//                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_270));
//                }

                Camera.Parameters camParams = cameraView.getCameraParams();
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

                // TAG_EXPOSURE_TIME TAG_ISO

                exif.saveAttributes();
            } catch (IOException e) {
                e.printStackTrace();
            }
            // Add the saved photo to the device gallery:
            try {
                String urlToAddedImage = MediaStore.Images.Media.insertImage(getContentResolver(), filePath, filename, getString(R.string.ppThread_photo_description));
                Log.d(TAG, "pictureCallback.onPictureTaken() :: urlToAddedImage = " + urlToAddedImage);
                String pathToAddedImage = Util.getFilePathFromUri(CameraActivity.this, Uri.parse(urlToAddedImage));
                Log.d(TAG, "pictureCallback.onPictureTaken() :: pathToAddedImage = " + pathToAddedImage);
                Util.copyExifTags(filePath, pathToAddedImage, bfOptions.outWidth, bfOptions.outHeight);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            // Upload the saved photo to Dropbox:
            dbUpldrThread.queuePhoto(filePath);

            postLastCapturedPhotoFilenameUpdate(filename);

            // 2) Send the captured photo data to another thread to save it on external storage:
//            photoProcsThread.setCamParams(cameraView.getCameraParams());
//            byte[] dataCopy = new byte[data.length];
//            System.arraycopy(data, 0, dataCopy, 0, data.length);
//            photoProcsThread.queuePhoto(dataCopy, System.currentTimeMillis(), devOrienAtCapture, lastLocation);

            // Restart camera preview:
            cameraView.restartPreview();

            // If the activity is not being closed and selected interval type is time,
            // schedule another photo capture:
            if (!isFinishing() && intervalType == INTERVAL_TYPE_TIME) {
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
     * An instance of the LocationListener interface whose callback method is invoked by the
     * Fused Provider API when a new location fix is acquired.
     */
    private LocationListener locationListener = new LocationListener() {

        /**
         * A callback method that will be called to notify the listener that device location has
         * changed.
         *
         * @param location Location acquired from any the Fused Provider.
         */
        @Override
        public void onLocationChanged(Location location) {
            // Update the location the camera uses to geo tag images:
            Log.d(TAG, "locationListener.onLocationChanged() :: location = " + location);
            updateShownLocationDataHelper();
            if (intervalType == INTERVAL_TYPE_DISTANCE && location != null) {
                if (lastLocation == null) {
                    lastLocation = location;
                    cameraView.setGeoTaggingLocation(location);

                    takePicInvocTimestamp = SystemClock.elapsedRealtime();
                    cameraView.takePicture(pictureCallback);
                    devOrienAtCapture = devOrien;
                } else {
                    float distance = lastLocation.distanceTo(location);
                    Log.d(TAG, "locationListener.onLocationChanged() :: distance = " + distance);

                    if (distance >= interval) {
                        lastLocation = location;
                        cameraView.setGeoTaggingLocation(location);

                        takePicInvocTimestamp = SystemClock.elapsedRealtime();
                        cameraView.takePicture(pictureCallback);
                        devOrienAtCapture = devOrien;
                    }
                }
            } else {
                lastLocation = location;
                // Update the location the camera uses to geo tag images:
                cameraView.setGeoTaggingLocation(location);
            }
        }
    };

    /**
     * A fragment to display a Google Play Services error dialog.
     */
    public static class ErrorDialogFragment extends DialogFragment {

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            // Get the error code and retrieve the appropriate dialog:
            int errorCode = this.getArguments().getInt(DIALOG_ERROR);
            return GooglePlayServicesUtil.getErrorDialog(errorCode, this.getActivity(), REQUEST_RESOLVE_ERROR);
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            ((CameraActivity) getActivity()).resolvingError = false;
        }
    }
}