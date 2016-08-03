package org.droidplanner.android.maps.providers.amap;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;


import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdate;
import com.amap.api.maps.SupportMapFragment;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.Projection;
import com.amap.api.maps.UiSettings;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.Polygon;
import com.amap.api.maps.model.PolygonOptions;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.maps.model.TileOverlay;
import com.amap.api.maps.model.VisibleRegion;

import com.o3dr.android.client.Drone;
import com.o3dr.services.android.lib.coordinate.LatLong;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.property.FootPrint;
import com.o3dr.services.android.lib.drone.property.Gps;

import org.droidplanner.android.DroidPlannerApp;
import org.droidplanner.android.R;
import org.droidplanner.android.fragments.SettingsFragment;
import org.droidplanner.android.graphic.map.GraphicHome;
import org.droidplanner.android.maps.DPMap;
import org.droidplanner.android.maps.MarkerInfo;
import org.droidplanner.android.maps.providers.DPMapProvider;
import org.droidplanner.android.maps.providers.google_map.AMapPrefFragment;
import org.droidplanner.android.maps.providers.google_map.DownloadMapboxMapActivity;
import org.droidplanner.android.maps.providers.google_map.GoogleMapPrefConstants;
import org.droidplanner.android.maps.providers.google_map.GoogleMapPrefFragment;
import org.droidplanner.android.maps.providers.google_map.tiles.TileProviderManager;
import org.droidplanner.android.maps.providers.google_map.tiles.mapbox.offline.MapDownloader;
import org.droidplanner.android.utils.DroneHelper;
import org.droidplanner.android.utils.collection.HashBiMap;
import org.droidplanner.android.utils.prefs.AutoPanMode;
import org.droidplanner.android.utils.prefs.DroidPlannerPrefs;


import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

public class AMapFragment extends SupportMapFragment implements DPMap {

    private static final long USER_LOCATION_UPDATE_INTERVAL = 30000; // ms
    private static final long USER_LOCATION_UPDATE_FASTEST_INTERVAL = 5000; // ms
    private static final float USER_LOCATION_UPDATE_MIN_DISPLACEMENT = 0; // m

    private static final float GO_TO_MY_LOCATION_ZOOM = 17f;

    private static final int ONLINE_TILE_PROVIDER_Z_INDEX = -1;
    private static final int OFFLINE_TILE_PROVIDER_Z_INDEX = -2;

    private static final IntentFilter eventFilter = new IntentFilter();
    private static final String TAG = "AMapFragment";

    static {
        eventFilter.addAction(AttributeEvent.GPS_POSITION);
        eventFilter.addAction(SettingsFragment.ACTION_MAP_ROTATION_PREFERENCE_UPDATED);
    }

    private AMap mMap;

    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG, "eventReceiver " + action);
            switch (action) {
                case AttributeEvent.GPS_POSITION:
                    if (mPanMode.get() == AutoPanMode.DRONE) {
                        final Drone drone = getDroneApi();
                        if (!drone.isConnected())
                            return;

                        final Gps droneGps = drone.getAttribute(AttributeType.GPS);
                        if (droneGps != null && droneGps.isValid()) {
                            final LatLong droneLocation = droneGps.getPosition();
                            updateCamera(droneLocation);
                        }
                    }
                    break;

                case SettingsFragment.ACTION_MAP_ROTATION_PREFERENCE_UPDATED:
                    setupMapUI(mMap);

                    break;
            }
        }
    };

    private final HashBiMap<MarkerInfo, Marker> mBiMarkersMap = new HashBiMap<MarkerInfo, Marker>();

    private DroidPlannerPrefs mAppPrefs;

    private final AtomicReference<AutoPanMode> mPanMode = new AtomicReference<AutoPanMode>(
            AutoPanMode.DISABLED);

    private final Handler handler = new Handler();

    public AMapLocationClientOption mLocationOption = null;
    AMapLocationClient mLocationClient;

    private Marker userMarker;

    private Polyline flightPath;
    private Polyline missionPath;
    private Polyline mDroneLeashPath;
    private int maxFlightPathSize;

    /*
     * DP Map listeners
     */
    private OnMapClickListener mMapClickListener;
    private OnMapLongClickListener mMapLongClickListener;
    private OnMarkerClickListener mMarkerClickListener;
    private OnMarkerDragListener mMarkerDragListener;
    private android.location.LocationListener mLocationListener;

    protected boolean useMarkerClickAsMapClick = false;

    private List<Polygon> polygonsPaths = new ArrayList<Polygon>();

    protected DroidPlannerApp dpApp;
    private Polygon footprintPoly;

    /*
    Tile overlay
     */
    private TileOverlay onlineTileOverlay;
    private TileOverlay offlineTileOverlay;

    private TileProviderManager tileProviderManager;

    private LocalBroadcastManager lbm;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        dpApp = (DroidPlannerApp) activity.getApplication();
        Log.d(TAG,"onAttach");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup viewGroup, Bundle bundle) {
        Log.d(TAG,"onCreateView");
        setHasOptionsMenu(true);

        final FragmentActivity activity = getActivity();
        final Context context = activity.getApplicationContext();

        lbm = LocalBroadcastManager.getInstance(context);

        initMap();
        final View view = super.onCreateView(inflater, viewGroup, bundle);

        mAppPrefs = DroidPlannerPrefs.getInstance(context);
//        mGApiClientMgr = new AMapClientManager(context, new Handler());
//        mGApiClientMgr.setManagerListener(this);
        final Bundle args = getArguments();
        if (args != null) {
            maxFlightPathSize = args.getInt(EXTRA_MAX_FLIGHT_PATH_SIZE);
        }

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
//        mGApiClientMgr.start();
        setupMap();
//        mGApiClientMgr.addTask(mRequestLocationUpdateTask);
        lbm.registerReceiver(eventReceiver, eventFilter);
    }

    @Override
    public void onStop() {
        super.onStop();

//        mGApiClientMgr.addTask(mRemoveLocationUpdateTask);
        lbm.unregisterReceiver(eventReceiver);

//        mGApiClientMgr.stopSafely();
        if (mLocationClient.isStarted()) {
            mLocationClient.stopLocation();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater){
        super.onCreateOptionsMenu(menu, inflater);

        inflater.inflate(R.menu.menu_google_map, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item){
        switch(item.getItemId()){
            case R.id.menu_download_mapbox_map:
                startActivity(new Intent(getContext(), DownloadMapboxMapActivity.class));
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu){
        final MenuItem item = menu.findItem(R.id.menu_download_mapbox_map);
        if(item != null) {
            final boolean isEnabled = shouldShowDownloadMapMenuOption();
            item.setEnabled(isEnabled);
            item.setVisible(isEnabled);
        }

    }

    private void initMap() {
        Log.d(TAG, "setUpMapIfNeeded");
        // Make sure the map is initialized
        try {
            MapsInitializer.initialize(getActivity().getApplicationContext());
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        if (mMap == null) {
            mMap = getMap();
        }

        mLocationClient = new AMapLocationClient(dpApp);
//初始化定位参数
        mLocationOption = new AMapLocationClientOption();
//设置定位监听
        mLocationClient.setLocationListener(new AMapLocationListener() {
            @Override
            public void onLocationChanged(AMapLocation location) {
                if (location != null) {
                    if (location.getErrorCode() == 0) {
                        Log.d(TAG,"onLocationChanged: " + location.toStr());
                                //定位成功回调信息，设置相关消息
                        location.getLocationType();//获取当前定位结果来源，如网络定位结果，详见定位类型表
                        location.getLatitude();//获取纬度
                        location.getLongitude();//获取经度
                        location.getAccuracy();//获取精度信息
                        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        Date date = new Date(location.getTime());
                        df.format(date);//定位时间

                        //Update the user location icon.
                        if (userMarker == null) {
                            final MarkerOptions options = new MarkerOptions()
                                    .position(new LatLng(location.getLatitude(), location.getLongitude()))
                                    .draggable(false)
                                    .setFlat(true)
                                    .visible(true)
                                    .anchor(0.5f, 0.5f)
                                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.user_location));

                            userMarker = mMap.addMarker(options);

                        } else {
                            userMarker.setPosition(new LatLng(location.getLatitude(), location.getLongitude()));
                        }

                        if (mPanMode.get() == AutoPanMode.USER) {
                            Timber.d("User location changed.");
                            updateCamera(DroneHelper.LocationToCoord(location), (int) getMap().getCameraPosition().zoom);
                        }

                        if (mLocationListener != null) {
                            mLocationListener.onLocationChanged(location);
                        }

                    } else {
                        //显示错误信息ErrCode是错误码，errInfo是错误信息，详见错误码表。
                        Log.e("AmapError","location Error, ErrCode:"
                                + location.getErrorCode() + ", errInfo:"
                                + location.getErrorInfo());
                    }
                }
            }
        });
//设置定位模式为高精度模式，Battery_Saving为低功耗模式，Device_Sensors是仅设备模式
        mLocationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
//设置定位间隔,单位毫秒,默认为2000ms
        mLocationOption.setInterval(USER_LOCATION_UPDATE_FASTEST_INTERVAL);
//设置定位参数
        mLocationClient.setLocationOption(mLocationOption);
// 此方法为每隔固定时间会发起一次定位请求，为了减少电量消耗或网络流量消耗，
// 注意设置合适的定位时间的间隔（最小间隔支持为2000ms），并且在合适时间调用stopLocation()方法来取消定位请求
// 在定位结束后，在合适的生命周期调用onDestroy()方法
// 在单次定位情况下，定位无论成功与否，都无需调用stopLocation()方法移除请求，定位sdk内部会移除
//启动定位
        mLocationClient.startLocation();
    }

    private boolean shouldShowDownloadMapMenuOption(){
        final Context context = getContext();
        final @GoogleMapPrefConstants.TileProvider String tileProvider = GoogleMapPrefFragment.PrefManager
                .getMapTileProvider(context);

        return (GoogleMapPrefConstants.MAPBOX_TILE_PROVIDER.equals(tileProvider)
            || GoogleMapPrefConstants.ARC_GIS_TILE_PROVIDER.equals(tileProvider))
                && GoogleMapPrefFragment.PrefManager.addDownloadMenuOption(context);
    }

    @Override
    public void clearFlightPath() {
        if (flightPath != null) {
            List<LatLng> oldFlightPath = flightPath.getPoints();
            oldFlightPath.clear();
            flightPath.setPoints(oldFlightPath);
        }
    }

    @Override
    public void downloadMapTiles(MapDownloader mapDownloader, VisibleMapArea mapRegion, int minimumZ, int maximumZ) {
        if(tileProviderManager == null)
            return;

        tileProviderManager.downloadMapTiles(mapDownloader, mapRegion, minimumZ, maximumZ);
    }

    @Override
    public LatLong getMapCenter() {
        return DroneHelper.LatLngToCoord(getMap().getCameraPosition().target);
    }

    @Override
    public float getMapZoomLevel() {
        return getMap().getCameraPosition().zoom;
    }

    @Override
    public float getMaxZoomLevel() {
        return getMap().getMaxZoomLevel();
    }

    @Override
    public float getMinZoomLevel() {
        return getMap().getMinZoomLevel();
    }

    @Override
    public void selectAutoPanMode(AutoPanMode target) {
        final AutoPanMode currentMode = mPanMode.get();
        if (currentMode == target)
            return;

        setAutoPanMode(currentMode, target);
    }

    private Drone getDroneApi() {
        return dpApp.getDrone();
    }

    private void setAutoPanMode(AutoPanMode current, AutoPanMode update) {
        mPanMode.compareAndSet(current, update);
    }

    @Override
    public DPMapProvider getProvider() {
        return DPMapProvider.AMAP;
    }

    @Override
    public void addFlightPathPoint(LatLong coord) {
        final LatLng position = DroneHelper.CoordToAMapLatLang(coord);

        if (maxFlightPathSize > 0) {
            if (flightPath == null) {
                PolylineOptions flightPathOptions = new PolylineOptions();
                flightPathOptions.color(FLIGHT_PATH_DEFAULT_COLOR)
                        .width(FLIGHT_PATH_DEFAULT_WIDTH).zIndex(1);
                flightPath = getMap().addPolyline(flightPathOptions);
            }

            List<LatLng> oldFlightPath = flightPath.getPoints();
            if (oldFlightPath.size() > maxFlightPathSize) {
                oldFlightPath.remove(0);
            }
            oldFlightPath.add(position);
            flightPath.setPoints(oldFlightPath);
        }
    }

    @Override
    public void clearMarkers() {
        for (Marker marker : mBiMarkersMap.valueSet()) {
            marker.remove();
        }

        mBiMarkersMap.clear();
    }

    @Override
    public void updateMarker(MarkerInfo markerInfo) {
        updateMarker(markerInfo, markerInfo.isDraggable());
    }

    @Override
    public void updateMarker(MarkerInfo markerInfo, boolean isDraggable) {
        // if the drone hasn't received a gps signal yet
        final LatLong coord = markerInfo.getPosition();
        if (coord == null) {
            return;
        }

        final LatLng position = DroneHelper.CoordToAMapLatLang(coord);
        Marker marker = mBiMarkersMap.getValue(markerInfo);
        if (marker == null) {
            // Generate the marker
            generateMarker(markerInfo, position, isDraggable);
        } else {
            // Update the marker
            updateMarker(marker, markerInfo, position, isDraggable);
        }
    }

    private void generateMarker(MarkerInfo markerInfo, LatLng position, boolean isDraggable) {
        final MarkerOptions markerOptions = new MarkerOptions()
                .position(position)
                .draggable(isDraggable)
                .anchor(markerInfo.getAnchorU(), markerInfo.getAnchorV())
                .setInfoWindowOffset((int)markerInfo.getInfoWindowAnchorU(),
                        (int)markerInfo.getInfoWindowAnchorV())
                .snippet(markerInfo.getSnippet()).title(markerInfo.getTitle())
                .setFlat(markerInfo.isFlat()).visible(markerInfo.isVisible());

        final Bitmap markerIcon = markerInfo.getIcon(getResources());
        if (markerIcon != null) {
            markerOptions.icon(BitmapDescriptorFactory.fromBitmap(markerIcon));
        }

        Marker marker = getMap().addMarker(markerOptions);
        mBiMarkersMap.put(markerInfo, marker);
    }

    private void updateMarker(Marker marker, MarkerInfo markerInfo, LatLng position,
                              boolean isDraggable) {
        final Bitmap markerIcon = markerInfo.getIcon(getResources());
        if (markerIcon != null) {
            marker.setIcon(BitmapDescriptorFactory.fromBitmap(markerIcon));
        }

//        marker.setAlpha(markerInfo.getAlpha());
        marker.setAnchor(markerInfo.getAnchorU(), markerInfo.getAnchorV());
//        marker.setInfoWindowAnchor(markerInfo.getInfoWindowAnchorU(),
//                markerInfo.getInfoWindowAnchorV());
        marker.setPosition(position);
//        marker.setRotation(markerInfo.getRotation());
        marker.setSnippet(markerInfo.getSnippet());
        marker.setTitle(markerInfo.getTitle());
        marker.setDraggable(isDraggable);
        marker.setFlat(markerInfo.isFlat());
        marker.setVisible(markerInfo.isVisible());
    }

    @Override
    public void updateMarkers(List<MarkerInfo> markersInfos) {
        for (MarkerInfo info : markersInfos) {
            updateMarker(info);
        }
    }

    @Override
    public void updateMarkers(List<MarkerInfo> markersInfos, boolean isDraggable) {
        for (MarkerInfo info : markersInfos) {
            updateMarker(info, isDraggable);
        }
    }

    @Override
    public Set<MarkerInfo> getMarkerInfoList() {
        return new HashSet<MarkerInfo>(mBiMarkersMap.keySet());
    }

    @Override
    public List<LatLong> projectPathIntoMap(List<LatLong> path) {
        List<LatLong> coords = new ArrayList<LatLong>();
        Projection projection = getMap().getProjection();

        for (LatLong point : path) {
            LatLng coord = projection.fromScreenLocation(new Point((int) point
                    .getLatitude(), (int) point.getLongitude()));
            coords.add(DroneHelper.LatLngToCoord(coord));
        }

        return coords;
    }

    @Override
    public void removeMarkers(Collection<MarkerInfo> markerInfoList) {
        if (markerInfoList == null || markerInfoList.isEmpty()) {
            return;
        }

        for (MarkerInfo markerInfo : markerInfoList) {
            Marker marker = mBiMarkersMap.getValue(markerInfo);
            if (marker != null) {
                marker.remove();
                mBiMarkersMap.removeKey(markerInfo);
            }
        }
    }

    @Override
    public void setMapPadding(int left, int top, int right, int bottom) {
//        getMap().setPadding(left, top, right, bottom);
    }

    @Override
    public void setOnMapClickListener(OnMapClickListener listener) {
        mMapClickListener = listener;
    }

    @Override
    public void setOnMapLongClickListener(OnMapLongClickListener listener) {
        mMapLongClickListener = listener;
    }

    @Override
    public void setOnMarkerDragListener(OnMarkerDragListener listener) {
        mMarkerDragListener = listener;
    }

    @Override
    public void setOnMarkerClickListener(OnMarkerClickListener listener) {
        mMarkerClickListener = listener;
    }

    @Override
    public void setLocationListener(android.location.LocationListener receiver) {
        mLocationListener = receiver;

        //Update the listener with the last received location
        if (mLocationListener != null) {
//            mGApiClientMgr.addTask(requestLastLocationTask);

            final Location lastLocation = mLocationClient.getLastKnownLocation();
            if (lastLocation != null && mLocationListener != null) {
                mLocationListener.onLocationChanged(lastLocation);
            }
        }
    }

    private void updateCamera(final LatLong coord) {
        if (coord != null) {

            final float zoomLevel = mMap.getCameraPosition().zoom;
            updateCamera(coord, zoomLevel);
        }
    }

    @Override
    public void updateCamera(final LatLong coord, final float zoomLevel) {
        Log.d(TAG, "updateCamera");
        if (coord != null) {

            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    DroneHelper.CoordToAMapLatLang(coord), zoomLevel));
        }
    }

    @Override
    public void updateCameraBearing(float bearing) {
        final CameraPosition cameraPosition = new CameraPosition(DroneHelper.CoordToAMapLatLang
                (getMapCenter()), getMapZoomLevel(), 0, bearing);
        getMap().animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
    }

    @Override
    public void updateDroneLeashPath(PathSource pathSource) {
        List<LatLong> pathCoords = pathSource.getPathPoints();
        final List<LatLng> pathPoints = new ArrayList<LatLng>(pathCoords.size());
        for (LatLong coord : pathCoords) {
            pathPoints.add(DroneHelper.CoordToAMapLatLang(coord));
        }

        if (mDroneLeashPath == null) {
            final PolylineOptions flightPath = new PolylineOptions();
            flightPath.color(DRONE_LEASH_DEFAULT_COLOR).width(
                    DroneHelper.scaleDpToPixels(DRONE_LEASH_DEFAULT_WIDTH, getResources()));

//            getMapAsync(new OnMapReadyCallback() {
//                @Override
//                public void onMapReady(GoogleMap googleMap) {
//                    mDroneLeashPath = getMap().addPolyline(flightPath);
//                    mDroneLeashPath.setPoints(pathPoints);
//                }
//            });
            mDroneLeashPath = getMap().addPolyline(flightPath);
            mDroneLeashPath.setPoints(pathPoints);
        }
        else {
            mDroneLeashPath.setPoints(pathPoints);
        }
    }

    @Override
    public void updateMissionPath(PathSource pathSource) {
        List<LatLong> pathCoords = pathSource.getPathPoints();
        final List<LatLng> pathPoints = new ArrayList<>(pathCoords.size());
        for (LatLong coord : pathCoords) {
            pathPoints.add(DroneHelper.CoordToAMapLatLang(coord));
        }

        if (missionPath == null) {
            final PolylineOptions pathOptions = new PolylineOptions();
            pathOptions.color(MISSION_PATH_DEFAULT_COLOR).width(MISSION_PATH_DEFAULT_WIDTH);
            missionPath = getMap().addPolyline(pathOptions);
            missionPath.setPoints(pathPoints);
        }
        else {
            missionPath.setPoints(pathPoints);
        }
    }


    @Override
    public void updatePolygonsPaths(List<List<LatLong>> paths) {
        for (Polygon poly : polygonsPaths) {
            poly.remove();
        }

        for (List<LatLong> contour : paths) {
            PolygonOptions pathOptions = new PolygonOptions();
            pathOptions.strokeColor(POLYGONS_PATH_DEFAULT_COLOR).strokeWidth(
                    POLYGONS_PATH_DEFAULT_WIDTH);
            final List<LatLng> pathPoints = new ArrayList<LatLng>(contour.size());
            for (LatLong coord : contour) {
                pathPoints.add(DroneHelper.CoordToAMapLatLang(coord));
            }
            pathOptions.addAll(pathPoints);
            polygonsPaths.add(getMap().addPolygon(pathOptions));
        }

    }

    @Override
    public void addCameraFootprint(FootPrint footprintToBeDraw) {
        PolygonOptions pathOptions = new PolygonOptions();
        pathOptions.strokeColor(FOOTPRINT_DEFAULT_COLOR).strokeWidth(FOOTPRINT_DEFAULT_WIDTH);
        pathOptions.fillColor(FOOTPRINT_FILL_COLOR);

        for (LatLong vertex : footprintToBeDraw.getVertexInGlobalFrame()) {
            pathOptions.add(DroneHelper.CoordToAMapLatLang(vertex));
        }
        getMap().addPolygon(pathOptions);

    }

    /**
     * Save the map camera state on a preference file
     * http://stackoverflow.com/questions
     * /16697891/google-maps-android-api-v2-restoring
     * -map-state/16698624#16698624
     */
    @Override
    public void saveCameraPosition() {
        final AMap googleMap = getMap();
        if(googleMap == null)
            return;

        CameraPosition camera = googleMap.getCameraPosition();
        mAppPrefs.prefs.edit()
                .putFloat(PREF_LAT, (float) camera.target.latitude)
                .putFloat(PREF_LNG, (float) camera.target.longitude)
                .putFloat(PREF_BEA, camera.bearing)
                .putFloat(PREF_TILT, camera.tilt)
                .putFloat(PREF_ZOOM, camera.zoom).apply();
    }

    @Override
    public void loadCameraPosition() {
        //getMapAsync(loadCameraPositionTask);
        final SharedPreferences settings = mAppPrefs.prefs;

        final CameraPosition.Builder camera = new CameraPosition.Builder();
        camera.bearing(settings.getFloat(PREF_BEA, DEFAULT_BEARING));
        camera.tilt(settings.getFloat(PREF_TILT, DEFAULT_TILT));
        camera.zoom(settings.getFloat(PREF_ZOOM, DEFAULT_ZOOM_LEVEL));
        camera.target(new LatLng(settings.getFloat(PREF_LAT, DEFAULT_LATITUDE),
                settings.getFloat(PREF_LNG, DEFAULT_LONGITUDE)));

        mMap.moveCamera(CameraUpdateFactory.newCameraPosition(camera.build()));
    }

    private void setupMap() {
        Log.d(TAG,"------setupMap");

//        getMapAsync(setupMapTask);
        setupMapUI(mMap);
        setupMapOverlay(mMap);
        setupMapListeners(mMap);
    }

    @Override
    public void zoomToFit(List<LatLong> coords) {
        if (!coords.isEmpty()) {
            final List<LatLng> points = new ArrayList<LatLng>();
            for (LatLong coord : coords){
                points.add(DroneHelper.CoordToAMapLatLang(coord));
            }

            final LatLngBounds bounds = getBounds(points);

            final Activity activity = getActivity();
            if (activity == null)
                return;

            final View rootView = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
            if (rootView == null)
                return;

            final int height = rootView.getHeight();
            final int width = rootView.getWidth();
            Timber.d("Screen W %d, H %d", width, height);
            if (height > 0 && width > 0) {
                CameraUpdate animation = CameraUpdateFactory.newLatLngBounds(bounds, width, height, 100);
                mMap.animateCamera(animation);
            }

        }
    }

    @Override
    public void zoomToFitMyLocation(final List<LatLong> coords) {

        final Location myLocation = mLocationClient.getLastKnownLocation();
        if (myLocation != null) {
            final List<LatLong> updatedCoords = new ArrayList<>(coords);
            updatedCoords.add(DroneHelper.LocationToCoord(myLocation));
            zoomToFit(updatedCoords);
        } else {
            zoomToFit(coords);
        }
    }

    @Override
    public void goToMyLocation() {
        Log.d(TAG, "goToMyLocation");
//        if (!mGApiClientMgr.addTask(mGoToMyLocationTask)) {
//            Timber.e("Unable to add  client task.");
//        }

        final Location myLocation = mLocationClient.getLastKnownLocation();
        if (myLocation != null) {
            updateCamera(DroneHelper.LocationToCoord(myLocation), GO_TO_MY_LOCATION_ZOOM);

            if (mLocationListener != null)
                mLocationListener.onLocationChanged(myLocation);
        }
    }

    @Override
    public void goToDroneLocation() {
        Log.d(TAG,"goToDroneLocation");
        Drone dpApi = getDroneApi();
        if (!dpApi.isConnected())
            return;

        Gps gps = dpApi.getAttribute(AttributeType.GPS);
        if (!gps.isValid()) {
            Toast.makeText(getActivity().getApplicationContext(), R.string.drone_no_location, Toast.LENGTH_SHORT).show();
            return;
        }

        final float currentZoomLevel = getMap().getCameraPosition().zoom;
        final LatLong droneLocation = gps.getPosition();
        updateCamera(droneLocation, (int) currentZoomLevel);
    }

    private void setupMapListeners(AMap aMap) {
        final AMap.OnMapClickListener onMapClickListener = new AMap.OnMapClickListener() {
            @Override
            public void onMapClick(LatLng latLng) {
                if (mMapClickListener != null) {
                    mMapClickListener.onMapClick(DroneHelper.LatLngToCoord(latLng));
                }
            }
        };
        aMap.setOnMapClickListener(onMapClickListener);

        aMap.setOnMapLongClickListener(new AMap.OnMapLongClickListener() {
            @Override
            public void onMapLongClick(LatLng latLng) {
                if (mMapLongClickListener != null) {
                    mMapLongClickListener.onMapLongClick(DroneHelper.LatLngToCoord(latLng));
                }
            }
        });

        aMap.setOnMarkerDragListener(new AMap.OnMarkerDragListener() {
            @Override
            public void onMarkerDragStart(Marker marker) {
                if (mMarkerDragListener != null) {
                    final MarkerInfo markerInfo = mBiMarkersMap.getKey(marker);
                    if(!(markerInfo instanceof GraphicHome)) {
                        markerInfo.setPosition(DroneHelper.LatLngToCoord(marker.getPosition()));
                        mMarkerDragListener.onMarkerDragStart(markerInfo);
                    }
                }
            }

            @Override
            public void onMarkerDrag(Marker marker) {
                if (mMarkerDragListener != null) {
                    final MarkerInfo markerInfo = mBiMarkersMap.getKey(marker);
                    if(!(markerInfo instanceof GraphicHome)) {
                        markerInfo.setPosition(DroneHelper.LatLngToCoord(marker.getPosition()));
                        mMarkerDragListener.onMarkerDrag(markerInfo);
                    }
                }
            }

            @Override
            public void onMarkerDragEnd(Marker marker) {
                if (mMarkerDragListener != null) {
                    final MarkerInfo markerInfo = mBiMarkersMap.getKey(marker);
                    markerInfo.setPosition(DroneHelper.LatLngToCoord(marker.getPosition()));
                    mMarkerDragListener.onMarkerDragEnd(markerInfo);
                }
            }
        });

        aMap.setOnMarkerClickListener(new AMap.OnMarkerClickListener() {
            @Override
            public boolean onMarkerClick(Marker marker) {
                if (useMarkerClickAsMapClick) {
                    onMapClickListener.onMapClick(marker.getPosition());
                    return true;
                }

                if (mMarkerClickListener != null) {
                    final MarkerInfo markerInfo = mBiMarkersMap.getKey(marker);
                    if (markerInfo != null)
                        return mMarkerClickListener.onMarkerClick(markerInfo);
                }
                return false;
            }
        });
    }

    private void setupMapUI(AMap map) {
        map.setMyLocationEnabled(false);
        UiSettings mUiSettings = map.getUiSettings();
        mUiSettings.setMyLocationButtonEnabled(false);
        mUiSettings.setCompassEnabled(true);
        mUiSettings.setTiltGesturesEnabled(true);
        mUiSettings.setZoomControlsEnabled(true);
        mUiSettings.setRotateGesturesEnabled(mAppPrefs.isMapRotationEnabled());
    }

    private void setupMapOverlay(AMap map) {
        final Context context = getContext();
        if(context == null)
            return;

        final @GoogleMapPrefConstants.TileProvider String tileProvider = AMapPrefFragment.PrefManager.getMapTileProvider(context);
        switch(tileProvider){
            case GoogleMapPrefConstants.GOOGLE_TILE_PROVIDER:
                setupGoogleTileProvider(context, map);
                break;

            case GoogleMapPrefConstants.AMAP_TILE_PROVIDER:
                setupAMapTileProvider(context, map);
                break;

        }
    }

    private void setupGoogleTileProvider(Context context, AMap map){
        //Reset the tile provider manager
        tileProviderManager = null;

        //Remove the mapbox tile providers
        if(offlineTileOverlay != null){
            offlineTileOverlay.remove();
            offlineTileOverlay = null;
        }

        if(onlineTileOverlay != null){
            onlineTileOverlay.remove();
            onlineTileOverlay = null;
        }

        map.setMapType(GoogleMapPrefFragment.PrefManager.getMapType(context));
    }

    private void setupAMapTileProvider(Context context, AMap map){
        //Reset the tile provider manager
        tileProviderManager = null;
        map.setMapType(AMapPrefFragment.PrefManager.getMapType(context));
    }

    protected void clearMap() {
        mMap.clear();
        setupMapOverlay(mMap);
    }

    private LatLngBounds getBounds(List<LatLng> pointsList) {
        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        for (LatLng point : pointsList) {
            builder.include(point);
        }
        return builder.build();
    }

    public double getMapRotation() {
        AMap map = getMap();
        if (map != null) {
            return map.getCameraPosition().bearing;
        } else {
            return 0;
        }
    }

    public VisibleMapArea getVisibleMapArea(){
        final AMap map = getMap();
        if(map == null)
            return null;

        final VisibleRegion mapRegion = map.getProjection().getVisibleRegion();
        return new VisibleMapArea(DroneHelper.LatLngToCoord(mapRegion.farLeft),
                DroneHelper.LatLngToCoord(mapRegion.nearLeft),
                DroneHelper.LatLngToCoord(mapRegion.nearRight),
                DroneHelper.LatLngToCoord(mapRegion.farRight));
    }

    @Override
    public void skipMarkerClickEvents(boolean skip) {
        useMarkerClickAsMapClick = skip;
    }

    @Override
    public void updateRealTimeFootprint(FootPrint footprint) {
        List<LatLong> pathPoints = footprint == null
                ? Collections.<LatLong>emptyList()
                : footprint.getVertexInGlobalFrame();

        if (pathPoints.isEmpty()) {
            if (footprintPoly != null) {
                footprintPoly.remove();
                footprintPoly = null;
            }
        } else {
            if (footprintPoly == null) {
                PolygonOptions pathOptions = new PolygonOptions()
                        .strokeColor(FOOTPRINT_DEFAULT_COLOR)
                        .strokeWidth(FOOTPRINT_DEFAULT_WIDTH)
                        .fillColor(FOOTPRINT_FILL_COLOR);

                for (LatLong vertex : pathPoints) {
                    pathOptions.add(DroneHelper.CoordToAMapLatLang(vertex));
                }
                footprintPoly = getMap().addPolygon(pathOptions);
            } else {
                List<LatLng> list = new ArrayList<LatLng>();
                for (LatLong vertex : pathPoints) {
                    list.add(DroneHelper.CoordToAMapLatLang(vertex));
                }
                footprintPoly.setPoints(list);
            }
        }

    }
}
