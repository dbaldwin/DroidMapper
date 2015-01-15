package com.droidmapper;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.session.AppKeyPair;
import com.droidmapper.util.Constants;

/**
 * This is the main activity and the starting point of the application. It creates the main screen
 * GUI on the device's screen, handles user input and allows him/her to launch the camera screen.
 */
public class MainActivity extends ActionBarActivity {

    // Constants used as keys for the values saved in preferences:
    private static final String PREF_KEY_DB_OAUTH2_ACCESS_TOKEN = "PREF_KEY_DB_OAUTH2_ACCESS_TOKEN";
    private static final String PREF_KEY_INTERVAL = "PREF_KEY_INTERVAL";
    private static final String PREF_KEY_DELAY = "PREF_KEY_DELAY";
    private static final String PREF_KEY_SIZE = "PREF_KEY_SIZE";

    // Views:
    private Spinner spinnerInterval, spinnerDelay, spinnerSize;
    private Button buttonStart;

    // Other:
    private DropboxAPI<AndroidAuthSession> dropboxApi;
    private SharedPreferences sharedPrefs;

    /**
     * A framework method that is invoked by the system when this activity is first created. It sets
     * up its GUI.
     *
     * @param savedInstanceState If the activity is being re-initialized after previously being shut
     *                           down then this Bundle contains the data it most recently supplied
     *                           in onSaveInstanceState(Bundle), otherwise it is <b>null</b>.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Inflates the GUI defined in the XML file:
        setContentView(R.layout.activity_main);

        // Get references to views defined in the GUI:
        spinnerInterval = (Spinner) findViewById(R.id.activityMain_spinnerInterval);
        spinnerDelay = (Spinner) findViewById(R.id.activityMain_spinnerDelay);
        spinnerSize = (Spinner) findViewById(R.id.activityMain_spinnerSize);
        buttonStart = (Button) findViewById(R.id.activityMain_buttonStart);

        // Listen for spinner selected item changes:
        spinnerInterval.setOnItemSelectedListener(onItemSelectedListener);
        spinnerDelay.setOnItemSelectedListener(onItemSelectedListener);
        spinnerSize.setOnItemSelectedListener(onItemSelectedListener);

        // Listen for clicks on the start button:
        buttonStart.setOnClickListener(onClickListener);

        // Load the previously selected values from preferences and set them in GUI:
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        int prefIntervalPos = sharedPrefs.getInt(PREF_KEY_INTERVAL, 0);
        int prefDelayPos = sharedPrefs.getInt(PREF_KEY_DELAY, 0);
        int prefSizePos = sharedPrefs.getInt(PREF_KEY_SIZE, 0);
        spinnerInterval.setSelection(prefIntervalPos);
        spinnerDelay.setSelection(prefDelayPos);
        spinnerSize.setSelection(prefSizePos);

        // Initialize the Dropbox API:
        if (!sharedPrefs.contains(PREF_KEY_DB_OAUTH2_ACCESS_TOKEN)) {
            AppKeyPair appKeys = new AppKeyPair(Constants.APP_KEY, Constants.APP_SECRET);
            AndroidAuthSession session = new AndroidAuthSession(appKeys, Constants.ACCESS_TYPE);
            dropboxApi = new DropboxAPI<AndroidAuthSession>(session);
        }
    }

    /**
     * Called after onRestoreInstanceState(Bundle), onRestart(), or onPause(), for this activity to
     * start interacting with the user. It completes the Dropbox API authentication and saves its
     * access token for later use.
     */
    @Override
    public void onResume() {
        super.onResume();
        if (dropboxApi != null && dropboxApi.getSession().authenticationSuccessful() && !sharedPrefs.contains(PREF_KEY_DB_OAUTH2_ACCESS_TOKEN)) {
            try {
                // Required to complete auth, sets the access token on the session
                dropboxApi.getSession().finishAuthentication();

                // Save the access token:
                String accessToken = dropboxApi.getSession().getOAuth2AccessToken();
                sharedPrefs.edit().putString(PREF_KEY_DB_OAUTH2_ACCESS_TOKEN, accessToken).commit();
            } catch (IllegalStateException e) {
                e.printStackTrace();
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * An instance of the OnItemSelectedListener interface that provides callback methods to the
     * Spinner views in this activity's GUI, so that they can inform the activity when the user has
     * made a new selection.
     */
    private AdapterView.OnItemSelectedListener onItemSelectedListener = new AdapterView.OnItemSelectedListener() {

        /**
         * Callback method to be invoked when an item in a Spinner has been selected. This callback
         * is invoked only when the newly selected position is different from the previously
         * selected position or if there was no selected item.
         *
         * @param parent The AdapterView where the selection happened.
         * @param view The view within the AdapterView that was clicked.
         * @param position The position of the view in the adapter.
         * @param id The row id of the item that is selected.
         */
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            // Save the newly selected value in preferences:
            if (parent == spinnerInterval) {
                sharedPrefs.edit().putInt(PREF_KEY_INTERVAL, position).commit();
            } else if (parent == spinnerDelay) {
                sharedPrefs.edit().putInt(PREF_KEY_DELAY, position).commit();
            } else if (parent == spinnerSize) {
                sharedPrefs.edit().putInt(PREF_KEY_SIZE, position).commit();
            }
        }

        /**
         * Callback method to be invoked when the selection disappears from this view. The selection
         * can disappear for instance when touch is activated or when the adapter becomes empty.
         *
         * @param parent The AdapterView that now contains no selected item.
         */
        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            // Do nothing
        }
    };

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
            if (view == buttonStart) {
                if (!sharedPrefs.contains(PREF_KEY_DB_OAUTH2_ACCESS_TOKEN)) {
                    // If needed authenticate the user with Dropbox:
                    dropboxApi.getSession().startOAuth2Authentication(MainActivity.this);
                } else {
                    // Load the selected values:
                    Resources res = getResources();
                    String authTkn = sharedPrefs.getString(PREF_KEY_DB_OAUTH2_ACCESS_TOKEN, null);
                    int interval = res.getIntArray(R.array.photo_interval_values)[spinnerInterval.getSelectedItemPosition()];
                    int delay = res.getIntArray(R.array.photo_delay_values)[spinnerDelay.getSelectedItemPosition()];
                    float size = Float.parseFloat(res.getStringArray(R.array.photo_size_values)[spinnerSize.getSelectedItemPosition()]);

                    // Launch the CameraActivity:
                    Intent intent = new Intent(MainActivity.this, CameraActivity.class);
                    intent.putExtra(CameraActivity.EXTRA_DB_OAUTH2_ACCESS_TOKEN, authTkn);
                    intent.putExtra(CameraActivity.EXTRA_INTERVAL, interval);
                    intent.putExtra(CameraActivity.EXTRA_DELAY, delay);
                    intent.putExtra(CameraActivity.EXTRA_SIZE, size);
                    startActivity(intent);
                }
            }
        }
    };
}
