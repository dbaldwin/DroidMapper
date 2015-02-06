package com.droidmapper.view;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.location.Location;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import java.io.IOException;

/**
 * A view that invokes the camera, renders its preview in the best possible resolution and provides
 * a way for clients to capture JPG encoded pictures.
 */
public class CameraView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = CameraView.class.getName();

    private boolean failedToConnectToCameraService;
    private SurfaceHolder previewHolder;
    private Camera camera;

    /**
     * Simple constructor to use when creating a CameraView from code.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     */
    public CameraView(Context context) {
        super(context);
        initialize();
    }

    /**
     * Constructor that is called when inflating a CameraView from XML. This is
     * called when a view is being constructed from an XML file, supplying
     * attributes that were specified in the XML file. This version uses a
     * default style of 0, so the only attribute values applied are those in the
     * Context's Theme and the given AttributeSet.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param attrs   The attributes of the XML tag that is inflating the view.
     */
    public CameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    /**
     * Perform inflation from XML and apply a class-specific base style. This
     * constructor of CameraView allows subclasses to use their own base style
     * when they are inflating.
     *
     * @param context  The Context the view is running in, through which it can
     *                 access the current theme, resources, etc.
     * @param attrs    the attributes of the XML tag that is inflating the view.
     * @param defStyle the default style to apply to this view. If 0, no style will
     *                 be applied (beyond what is included in the theme). This may
     *                 either be an attribute resource, whose value will be retrieved
     *                 from the current theme, or an explicit style resource.
     */
    public CameraView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initialize();
    }

    /**
     * This method is called immediately after this view's surface is first created. It turns on the
     * camera and sets its preview display.
     *
     * @param holder The SurfaceHolder whose surface is being created.
     */
    public void surfaceCreated(SurfaceHolder holder) {
        if (!failedToConnectToCameraService) {
            try {
                camera.setPreviewDisplay(previewHolder);
                camera.startPreview();
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * This is called immediately after any structural changes (format or size)
     * have been made to this view's surface. It sets camera parameters. It is
     * always called at least once, after surfaceCreated() method.
     *
     * @param holder The SurfaceHolder whose surface has changed.
     * @param format The new PixelFormat of the surface.
     * @param width  The new width of the surface.
     * @param height The new height of the surface.
     */
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged() :: View size is " + width + "x" + height);

        // Start rendering the camera preview on the screen.
        if (camera != null) {
            Camera.Parameters parameters = camera.getParameters();

            // Camera also supports a fixed set of sizes of pictures that can be taken with the
            // camera, figure out the maximal:
            Camera.Size pictureSize = null;
            for (Camera.Size size : parameters.getSupportedPictureSizes()) {
                if (pictureSize == null) {
                    pictureSize = size;
                } else {
                    if (size.width > pictureSize.width) {
                        pictureSize = size;
                    }
                }
            }

            if (pictureSize != null) {

                // Pick the best FPS range(min and max number of preview frames sent to the screen
                // each second:
                //int[] previewBestFpsRange = parameters.getSupportedPreviewFpsRange().get(0);

                // Set the parameters
                // Set preview size to 640x480 for now since we're not too concerned with previews
                parameters.setPreviewSize(640, 480);
                parameters.setPictureSize(pictureSize.width, pictureSize.height);
                parameters.setPreviewFormat(ImageFormat.NV21);
                parameters.setPictureFormat(ImageFormat.JPEG);
                parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
                parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_DAYLIGHT);
                parameters.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
                parameters.setColorEffect(Camera.Parameters.EFFECT_NONE);
                // Lock frame rate to 1 fps for now
                parameters.setPreviewFpsRange(1, 1);
                if (parameters.isZoomSupported()) {
                    parameters.setZoom(0);
                }
                try {
                    camera.setParameters(parameters);
                } catch (Exception e) {
                    Log.d(TAG, "camera.setParameters() exception: " + e.toString());
                }
            }

            camera.startPreview();
        }
    }

    /**
     * This is called immediately before this view's surface is being destroyed
     * to stop the camera preview and release it.
     *
     * @param holder The SurfaceHolder whose surface is being destroyed.
     */
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Stop rendering the camera preview on the screen.
        camera.cancelAutoFocus();
        camera.stopPreview();
        camera.setPreviewCallback(null);
        if (camera != null) {
            camera.release();
        }
        camera = null;
    }

    /**
     * Use this method to take a picture from camera. A JPEG encoded picture will delivered in a
     * onPictureTaken() callback of the PictureCallback instance supplied as parameter.
     *
     * @param pictureCallback The callback for JPEG image data.
     */
    public void takePicture(Camera.PictureCallback pictureCallback) {
        if (pictureCallback == null) {
            throw new NullPointerException("Picture callback parameter can't be null.");
        }
        camera.takePicture(null, null, null, pictureCallback);
    }

    /**
     * Restarts the camera preview after a photo is taken.
     */
    public void restartPreview() {
        if (!failedToConnectToCameraService) {
            camera.startPreview();
        }
    }

    /**
     * Sets the location the camera uses to geo tag images.
     *
     * @param deviceLocation The new location for geo tagging.
     */
    public void setGeoTaggingLocation(Location deviceLocation) {
        if (deviceLocation != null && camera != null && !failedToConnectToCameraService) {
            Camera.Parameters parameters = camera.getParameters();
            parameters.setGpsAltitude(deviceLocation.getAltitude());
            parameters.setGpsLatitude(deviceLocation.getLatitude());
            parameters.setGpsLongitude(deviceLocation.getLongitude());
            parameters.setGpsProcessingMethod(deviceLocation.getProvider());
            parameters.setGpsTimestamp(deviceLocation.getTime());
            camera.setParameters(parameters);
        }
    }

    /**
     * Helper method used by class constructors to initialize the preview holder and open the camera.
     */
    private void initialize() {
        if (!isInEditMode()) {
            // Initialize preview holder:
            previewHolder = getHolder();
            previewHolder.addCallback(this);

            // Open the camera:
            try {
                failedToConnectToCameraService = false;
                camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
                // camera.setDisplayOrientation(90);
            } catch (RuntimeException e) {
                e.printStackTrace();
                failedToConnectToCameraService = true;
                Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
