/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.kotlin.helloar

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.ar.core.Config
import com.google.ar.core.Config.InstantPlacementMode
import com.google.ar.core.Session
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper
import com.google.ar.core.examples.java.common.helpers.DepthSettings
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.kotlin.common.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
//import android.location.LocationRequest
import android.os.Handler
import android.os.Looper
import android.renderscript.RenderScript
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.ar.core.Pose

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
class HelloArActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "HelloArActivity"
  }

  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: HelloArView
  lateinit var renderer: HelloArRenderer

  val instantPlacementSettings = InstantPlacementSettings()
  val depthSettings = DepthSettings()
  lateinit var fusedLocationClient: FusedLocationProviderClient
  lateinit var locationRequest: LocationRequest
  lateinit var locationCallback: LocationCallback

  val lat2 = 22.294502 //22.292008
  val lng2 = 73.359828 //73.363306
  var hasPlacedTarget = false
  var originLat: Double? = null
  var originLng: Double? = null

  private fun bearingToTarget(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)

    val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2Rad)
    val x = kotlin.math.cos(lat1Rad) * kotlin.math.sin(lat2Rad) -
            kotlin.math.sin(lat1Rad) * kotlin.math.cos(lat2Rad) * kotlin.math.cos(dLon)

    var angle = Math.toDegrees(kotlin.math.atan2(y, x))

    if (angle < 0) angle += 360.0
    return angle
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ActivityCompat.requestPermissions(
      this,
      arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
      ),
      1001
    )

    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

    locationRequest = LocationRequest.Builder(
      Priority.PRIORITY_HIGH_ACCURACY,
      1000 // 1 second
    ).build()
    locationCallback = object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        val location = result.lastLocation ?: return

        val lat1 = location.latitude
        val lng1 = location.longitude

// Compute distance (in meters)
        val result = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, result)
        val distance = result[0]

// Compute bearing
        val bearing = bearingToTarget(lat1, lng1, lat2, lng2)

// Get anchor world position if exists
        val anchor = renderer.anchors.firstOrNull()
        val anchorMatrix = FloatArray(16)
        var ax: Float? = null
        var ay: Float? = null
        var az: Float? = null
        if (anchor != null) {
          anchor.pose.toMatrix(anchorMatrix, 0)
          ax = anchorMatrix[12]
          ay = anchorMatrix[13]
          az = anchorMatrix[14]
        }

// Tracking state (from last frame)
        val trackingState = renderer.lastTrackingState

// Update debug info on screen
        view.updateInfo(
          lat1,
          lng1,
          lat2,
          lng2,
          distance,
          bearing,
          ax,
          ay,
          az,
          trackingState,
          renderer.anchors.size
        )

// Now place anchor at target (not at your location)
        placeGpsAnchor(lat2, lng2)
        // Initialize origin first time only
        if (originLat == null) {
          originLat = lat1
          originLng = lng1
        }

        // Now place anchor at TARGET position

        // Place only once, and only after origin is set
        if (!hasPlacedTarget && originLat != null) {

          // Convert target to meters
          val (x, z) = convertLatLngToMeters(lat2, lng2)
          val distance = Math.sqrt((x * x + z * z).toDouble())

          // Skip if anchor would be inside the camera
          if (distance > 1.0) {
            placeGpsAnchor(lat2, lng2)
            hasPlacedTarget = true
            Log.d("GPS-ANCHOR", "Placed target anchor: $distance meters away")
          } else {
            Log.w("GPS-ANCHOR", "Skipping anchor — target too close: $distance meters")
          }
        }      }
    }
    // Setup ARCore session lifecycle helper and configuration.
    arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
    // If Session creation or Session.resume() fails, display a message and log detailed
    // information.
    arCoreSessionHelper.exceptionCallback =
      { exception ->
        val message =
          when (exception) {
            is UnavailableUserDeclinedInstallationException ->
              "Please install Google Play Services for AR"
            is UnavailableApkTooOldException -> "Please update ARCore"
            is UnavailableSdkTooOldException -> "Please update this app"
            is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
            is CameraNotAvailableException -> "Camera not available. Try restarting the app."
            else -> "Failed to create AR session: $exception"
          }
        Log.e(TAG, "ARCore threw an exception", exception)
        view.snackbarHelper.showError(this, message)
      }

    // Configure session features, including: Lighting Estimation, Depth mode, Instant Placement.
    arCoreSessionHelper.beforeSessionResume = ::configureSession
    lifecycle.addObserver(arCoreSessionHelper)

    // Set up the Hello AR renderer.
    renderer = HelloArRenderer(this)
    lifecycle.addObserver(renderer)

    // Set up Hello AR UI.
    view = HelloArView(this)
    lifecycle.addObserver(view)
    setContentView(view.root)

    // Sets up an example renderer using our HelloARRenderer.
    SampleRender(view.surfaceView, renderer, assets)

    depthSettings.onCreate(this)
    depthSettings.setUseDepthForOcclusion(false)

    instantPlacementSettings.onCreate(this)
  }

  // Configure the session, using Lighting Estimation, and Depth mode.
  fun configureSession(session: Session) {
    session.configure(
      session.config.apply {
        lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

        // Depth API is used if it is configured in Hello AR's settings.
        depthMode =
          if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            Config.DepthMode.AUTOMATIC
          } else {
            Config.DepthMode.DISABLED
          }

        // Instant Placement is used if it is configured in Hello AR's settings.
        instantPlacementMode =
          if (instantPlacementSettings.isInstantPlacementEnabled) {
            InstantPlacementMode.LOCAL_Y_UP
          } else {
            InstantPlacementMode.DISABLED
          }
      }
    )
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    results: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
        .show()
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this)
      }
      finish()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }

  fun getCurrentLocation(onResult: (Double, Double) -> Unit) {
    if (ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
      ) != PackageManager.PERMISSION_GRANTED &&
      ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      return
    }

    fusedLocationClient.lastLocation
      .addOnSuccessListener { location: Location? ->
        location?.let {
          onResult(it.latitude, it.longitude)
        }
      }
  }

  override fun onResume() {
    super.onResume()
    if (ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      return
    }

    fusedLocationClient.requestLocationUpdates(
      locationRequest,
      locationCallback,
      Looper.getMainLooper()
    )
  }
  override fun onPause() {
    super.onPause()
    fusedLocationClient.removeLocationUpdates(locationCallback)
  }
  fun convertLatLngToMeters(lat: Double, lng: Double): Pair<Float, Float> {
    val lat0 = originLat ?: return Pair(0f, 0f)
    val lng0 = originLng ?: return Pair(0f, 0f)

    val earthRadius = 6378137.0

    val dLat = Math.toRadians(lat - lat0)
    val dLng = Math.toRadians(lng - lng0)

    val x = (dLng * earthRadius * kotlin.math.cos(Math.toRadians(lat0))).toFloat()
    val z = (dLat * earthRadius).toFloat()

    // ARCore: +X = right, -Z = forward
    return Pair(x, -z)
  }

  fun placeGpsAnchor(lat: Double, lng: Double) {
    if (renderer.anchors.isNotEmpty()) return
    val (x, z) = convertLatLngToMeters(lat, lng)

    val distance = Math.sqrt((x * x + z * z).toDouble())

    // Avoid placing anchor inside camera
    if (distance < 1.0) {
      Log.w("GPS-ANCHOR", "Anchor too close ($distance m) — skipping")
      return
    }

    renderer.latestGpsAnchorRequest = Pair(x, z)
  }
}
