package fr.free.nrw.commons.nearby;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AlertDialog;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import dagger.android.AndroidInjection;
import fr.free.nrw.commons.CommonsApplication;
import fr.free.nrw.commons.R;
import fr.free.nrw.commons.location.LatLng;
import fr.free.nrw.commons.location.LocationServiceManager;
import fr.free.nrw.commons.location.LocationUpdateListener;
import fr.free.nrw.commons.theme.NavigationBaseActivity;
import fr.free.nrw.commons.utils.UriSerializer;
import fr.free.nrw.commons.utils.ViewUtil;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

import static fr.free.nrw.commons.location.LocationServiceManager.LOCATION_REQUEST;


public class NearbyActivity extends NavigationBaseActivity implements LocationUpdateListener {

    @BindView(R.id.progressBar)
    ProgressBar progressBar;
    private static final String MAP_LAST_USED_PREFERENCE = "mapLastUsed";

    @Inject
    LocationServiceManager locationManager;
    private LatLng locationLatLng;
    private Bundle bundle;
    private SharedPreferences sharedPreferences;
    private NearbyActivityMode viewMode;
    private Disposable placesDisposable;
    private boolean lockNearbyView; //Determines if the nearby places needs to be refreshed

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidInjection.inject(this);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        setContentView(R.layout.activity_nearby);
        ButterKnife.bind(this);
        bundle = new Bundle();
        initDrawer();
        initViewState();
    }

    private void initViewState() {
        if (sharedPreferences.getBoolean(MAP_LAST_USED_PREFERENCE, false)) {
            viewMode = NearbyActivityMode.MAP;
        } else {
            viewMode = NearbyActivityMode.LIST;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_nearby, menu);

        if (viewMode.isMap()) {
            MenuItem item = menu.findItem(R.id.action_toggle_view);
            item.setIcon(viewMode.getIcon());
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.action_latest_location_refresh:
                lockNearbyView(false);
                refreshLatestLocationView(true);
                return true;
            case R.id.action_toggle_view:
                viewMode = viewMode.toggle();
                item.setIcon(viewMode.getIcon());
                toggleView();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void requestLocationPermissions() {
        if (!isFinishing()) {
            locationManager.requestPermissions(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case LOCATION_REQUEST: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    refreshLatestLocationView(false);
                } else {
                    //If permission not granted, go to page that says Nearby Places cannot be displayed
                    hideProgressBar();
                    showLocationPermissionDeniedErrorDialog();
                }
            }
        }
    }

    private void showLocationPermissionDeniedErrorDialog() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.nearby_needs_permissions)
                .setCancelable(false)
                .setPositiveButton(R.string.give_permission, (dialog, which) -> {
                    //will ask for the location permission again
                    checkGps();
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    //dismiss dialog and finish activity
                    dialog.cancel();
                    finish();
                })
                .create()
                .show();
    }

    private void checkGps() {
        if (!locationManager.isProviderEnabled()) {
            Timber.d("GPS is not enabled");
            new AlertDialog.Builder(this)
                    .setMessage(R.string.gps_disabled)
                    .setCancelable(false)
                    .setPositiveButton(R.string.enable_gps,
                            (dialog, id) -> {
                                Intent callGPSSettingIntent = new Intent(
                                        android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                                Timber.d("Loaded settings page");
                                startActivityForResult(callGPSSettingIntent, 1);
                            })
                    .setNegativeButton(R.string.menu_cancel_upload, (dialog, id) -> {
                        showLocationPermissionDeniedErrorDialog();
                        dialog.cancel();
                    })
                    .create()
                    .show();
        } else {
            Timber.d("GPS is enabled");
            checkLocationPermission();
        }
    }

    private void checkLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (locationManager.isLocationPermissionGranted()) {
                refreshLatestLocationView(false);
            } else {
                // Should we show an explanation?
                if (locationManager.isPermissionExplanationRequired(this)) {
                    // Show an explanation to the user *asynchronously* -- don't block
                    // this thread waiting for the user's response! After the user
                    // sees the explanation, try again to request the permission.
                    new AlertDialog.Builder(this)
                            .setMessage(getString(R.string.location_permission_rationale_nearby))
                            .setPositiveButton("OK", (dialog, which) -> {
                                requestLocationPermissions();
                                dialog.dismiss();
                            })
                            .setNegativeButton("Cancel", (dialog, id) -> {
                                showLocationPermissionDeniedErrorDialog();
                                dialog.cancel();
                            })
                            .create()
                            .show();

                } else {
                    // No explanation needed, we can request the permission.
                    requestLocationPermissions();
                }
            }
        } else {
            refreshLatestLocationView(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1) {
            Timber.d("User is back from Settings page");
            refreshLatestLocationView(false);
        }
    }

    private void toggleView() {
        if (viewMode.isMap()) {
            setMapFragment();
        } else {
            setListFragment();
        }
        sharedPreferences.edit().putBoolean(MAP_LAST_USED_PREFERENCE, viewMode.isMap()).apply();
    }

    @Override
    protected void onStart() {
        super.onStart();
        locationManager.addLocationListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        locationManager.removeLocationListener(this);
        locationManager.unregisterLocationManager();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (placesDisposable != null) {
            placesDisposable.dispose();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lockNearbyView = false;
        checkGps();
    }

    /**
     * This method should be the single point to load/refresh nearby places
     *
     * @param isHardRefresh
     */
    private void refreshLatestLocationView(boolean isHardRefresh) {
        if (lockNearbyView) {
            return;
        }
        locationManager.registerLocationManager();
        LatLng lastLocation = locationManager.getLastLocation();
        if (locationLatLng != null && locationLatLng.equals(lastLocation)) { //refresh view only if location has changed
            if (isHardRefresh) {
                ViewUtil.showLongToast(this, R.string.nearby_location_has_not_changed);
            }
            return;
        }
        locationLatLng = lastLocation;

        if (locationLatLng == null) {
            Timber.d("Skipping update of nearby places as location is unavailable");
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        setupPlaceList(this);
    }

    public void refreshSelectedLocationView(LatLng custLatLang) {
        locationLatLng = custLatLang;

        if (locationLatLng == null) {
            Timber.d("Skipping update of nearby places as location is unavailable");
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        setupPlaceList(this);
    }

    private void setupPlaceList(Context context) {
        placesDisposable = Observable.fromCallable(() -> NearbyController
                .loadAttractionsFromLocation(locationLatLng, CommonsApplication.getInstance()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((result) -> {
                    populatePlaces(context, result);
                });
    }

    private void populatePlaces(Context context, List<Place> placeList) {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(Uri.class, new UriSerializer())
                .create();
        String gsonPlaceList = gson.toJson(placeList);
        String gsonCurLatLng = gson.toJson(locationLatLng);

        if (placeList.size() == 0) {
            int duration = Toast.LENGTH_SHORT;
            Toast toast = Toast.makeText(context, R.string.no_nearby, duration);
            toast.show();
        }

        bundle.clear();
        bundle.putString("PlaceList", gsonPlaceList);
        bundle.putString("CurLatLng", gsonCurLatLng);

        lockNearbyView(true);
        // Begin the transaction
        if (viewMode.isMap()) {
            setMapFragment();
        } else {
            setListFragment();
        }

        hideProgressBar();
    }

    private void lockNearbyView(boolean lock) {
        if (lock) {
            lockNearbyView = true;
            locationManager.unregisterLocationManager();
            locationManager.removeLocationListener(this);
        } else {
            lockNearbyView = false;
            locationManager.registerLocationManager();
            locationManager.addLocationListener(this);
        }
    }

    private void hideProgressBar() {
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
    }

    /**
     * Calls fragment for map view.
     */
    private void setMapFragment() {
        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
        Fragment fragment = new NearbyMapFragment();
        fragment.setArguments(bundle);
        fragmentTransaction.replace(R.id.container, fragment);
        fragmentTransaction.commitAllowingStateLoss();
    }

    /**
     * Calls fragment for list view.
     */
    private void setListFragment() {
        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
        Fragment fragment = new NearbyListFragment();
        fragment.setArguments(bundle);
        fragmentTransaction.replace(R.id.container, fragment);
        fragmentTransaction.commitAllowingStateLoss();
    }

    @Override
    public void onLocationChanged(LatLng latLng) {
        refreshLatestLocationView(false);
    }
}
