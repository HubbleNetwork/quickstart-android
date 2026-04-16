# Hubble Gateway SDK — Android Quickstart

A minimal Android app showing how to embed the [Hubble Gateway SDK](https://github.com/hubble-network/gateway-sdk-android) so any Android phone can act as a Hubble gateway — scanning for nearby Hubble Bluetooth devices and recording their location.

## What the app demonstrates

| Feature | Where to look |
|---|---|
| SDK initialization with NotificationConfig and SDK key | [`QuickstartApplication.java`](app/src/main/java/com/hubble/quickstart/gateway/QuickstartApplication.java) |
| Manifest permission declarations | [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) |
| Sequential runtime permission flow using `getMissingPermissionGroups` | [`MainActivity.java`](app/src/main/java/com/hubble/quickstart/gateway/MainActivity.java) |
| Starting and stopping background BLE scanning | `MainActivity.java` — `startScanning()` / `stopScanning()` |
| Active scanning (foreground service) | `MainActivity.java` — `startActiveScanning()` / `stopActiveScanning()` |
| Listening for scan results with device location and service UUID | [`MainActivity.java`](app/src/main/java/com/hubble/quickstart/gateway/MainActivity.java) — `scanListener` |

## Getting Started

### Prerequisites

- Android Studio
- Android SDK (minSdk 21)
- A physical device with Bluetooth LE support (BLE scanning does not work on the emulator)

### Build & Run

1. Clone this repository.
2. Add your Hubble SDK key to `local.properties` (this file is not checked into version control):
   ```properties
   hubbleSdkKey=your-sdk-key-here
   ```
3. Place your `google-services.json` in the `app/` directory. You can download this from your [Firebase Console](https://console.firebase.google.com/) project settings. This file is gitignored and required for crash reporting via Firebase Crashlytics.
4. Open `quickstart-android` in Android Studio.
5. Sync Gradle, then run the app on your device.

## Integrating the SDK into your own app

### 1. Add the dependency

```groovy
// build.gradle
dependencies {
    implementation "com.hubble.sdk:gateway-sdk:0.2.5"
}
```

### 2. Initialize in Application.onCreate()

Call `HubbleGatewaySDK.start()` once, as early as possible. The SDK retains only the application context. Pass your SDK key to enable scan result uploads to the Hubble backend.

```java
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        HubbleGatewaySDK.start(
                this,
                new HubbleGatewayConfig.Builder()
                        .sdkKey(BuildConfig.HUBBLE_SDK_KEY)
                        .build()
        );
    }
}
```

Set `hubbleSdkKey` in your `local.properties` and expose it as a `BuildConfig` field so it stays out of version control.

After `start()`, the SDK begins collecting device locations immediately if location permissions are granted, even without BLE permissions (location-only mode). BLE scanning starts once `BLUETOOTH_SCAN` is also granted. If no permissions have been granted yet, both are deferred until you call `startScanning()` after granting permissions. When an SDK key is configured, scan results are automatically uploaded to the Hubble backend.

### 3. Configure the foreground service notification

The SDK runs a foreground service for background BLE scanning. You must provide a `NotificationConfig` for the foreground service to work:

```java
HubbleGatewaySDK.start(
        this,
        new HubbleGatewayConfig.Builder()
                .sdkKey(BuildConfig.HUBBLE_SDK_KEY)
                .notificationConfig(
                        new NotificationConfig.Builder()
                                .title("My App")
                                .text("Scanning for nearby devices…")
                                .smallIcon(R.drawable.ic_notification)
                                .channelName("My App BLE Scan")
                                .build()
                )
                .build()
);
```

| Field | Description |
|---|---|
| `title` | Notification title |
| `text` | Notification body text |
| `smallIcon` | Drawable resource for the notification icon |
| `channelName` | Channel name visible in system notification settings |

The `respectBatterySaver` config option (default `true`) automatically reduces scan frequency when the device enters battery saver mode. The SDK uses an adaptive scan strategy internally — scanning more frequently when the device is moving and less when stationary.

### 4. Add manifest permissions

The SDK declares `INTERNET` in its own manifest (auto-merged, no user grant required). All other permissions must be declared by your app. Add the following to your `AndroidManifest.xml`:

```xml
<!-- Location — FINE is required on all API levels; COARSE is a prerequisite for FINE on 31+ -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- BLE scanning (optional — without this the SDK operates in location-only mode) -->
<!-- BLUETOOTH_SCAN for API 31+; legacy BLUETOOTH/BLUETOOTH_ADMIN for API < 31 -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
    android:maxSdkVersion="30" />

<!-- Foreground service — required for the SDK's periodic scan worker -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

<!-- Foreground service notification on API 33+ -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Background location (must be requested separately from foreground location on API 30+) -->
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<!-- Optional — improves adaptive scan scheduling via activity recognition -->
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

### 5. Request runtime permissions using `getMissingPermissionGroups`

The SDK provides `SdkCapabilities.getMissingPermissionGroups()` which returns permissions
pre-grouped in the correct request order. Each group must be requested sequentially — wait for
the result of one group before requesting the next.

| Group | Permissions | Notes |
|---|---|---|
| 1 (foreground) | `ACCESS_FINE_LOCATION`, `BLUETOOTH_SCAN` (optional), `POST_NOTIFICATIONS` | Requested together; SDK works in location-only mode without `BLUETOOTH_SCAN` |
| 2 (background) | `ACCESS_BACKGROUND_LOCATION` | Must be requested **separately** from foreground location on API 30+. On API 30+, direct the user to Settings instead of using `requestPermissions()`. |
| 3 (optional) | `ACTIVITY_RECOGNITION` | Improves adaptive scan scheduling; SDK works without it |

> Only permissions relevant to the current API level and not yet granted are returned. Empty groups are excluded.

```java
private List<List<String>> pendingPermissionGroups;
private boolean isRequestingPermissions = false;

private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                results -> {
                    // Continue to the next group regardless of this group's result.
                    requestNextPermissionGroup();
                });

private void ensureScanning() {
    if (HubbleGatewaySDK.isScanning()) return;

    pendingPermissionGroups = SdkCapabilities.getMissingPermissionGroups(this);

    if (pendingPermissionGroups.isEmpty()) {
        if (SdkCapabilities.isMinimallyOperational(this)) {
            HubbleGatewaySDK.startScanning();
        }
        return;
    }

    if (!isRequestingPermissions) {
        requestNextPermissionGroup();
    }
}

private void requestNextPermissionGroup() {
    if (pendingPermissionGroups == null || pendingPermissionGroups.isEmpty()) {
        isRequestingPermissions = false;
        if (SdkCapabilities.isMinimallyOperational(this)) {
            HubbleGatewaySDK.startScanning();
        }
        return;
    }

    isRequestingPermissions = true;
    List<String> group = pendingPermissionGroups.remove(0);

    // On API 30+, the standard permission dialog doesn't work for background location.
    // Direct the user to the app's Settings page instead.
    boolean isBackgroundGroup = group.size() == 1
            && "android.permission.ACCESS_BACKGROUND_LOCATION".equals(group.get(0));

    if (isBackgroundGroup && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("Background location (\"Allow all the time\") is required "
                        + "so this device can scan for Hubble devices when the app "
                        + "is not open.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    Intent intent = new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                    // onResume will re-run ensureScanning() when the user returns
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        requestNextPermissionGroup())
                .show();
        return;
    }

    boolean shouldShowRationale = false;
    for (String perm : group) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
            shouldShowRationale = true;
            break;
        }
    }

    if (shouldShowRationale) {
        new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("Location, Bluetooth, and notification permissions are needed "
                        + "so this device can act as a Hubble gateway.")
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        permissionLauncher.launch(group.toArray(new String[0])))
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        requestNextPermissionGroup())
                .show();
    } else {
        permissionLauncher.launch(group.toArray(new String[0]));
    }
}
```

Call `ensureScanning()` in your `onResume()` to automatically request permissions and start
scanning. The sequential flow handles all API levels correctly — on older devices, only the
relevant permissions are requested.

**Important:** Always call `startScanning()` after new permissions are granted. The SDK cannot detect permission changes on its own — it relies on your app to notify it when scanning is allowed to proceed. If you call `startScanning()` when scanning is already active, it is a safe no-op.

### 6. Listen for scan results

Register a `GatewayScanListener` to receive scan results as they arrive. Each `GatewayScanResult` contains the Hubble device's BLE advertisement data and the phone's location at the time of the scan:

```java
private final GatewayScanListener scanListener = result -> {
    String address       = result.getDeviceAddress();    // "AA:BB:CC:DD:EE:FF"
    int rssi             = result.getRssi();             // Signal strength in dBm
    long timestamp       = result.getTimestampMillis();  // When the advertisement was seen
    ParcelUuid uuid      = result.getServiceUuid();      // Hubble (0xFCA6) or Tile (0xFEED)
    byte[] serviceData   = result.getServiceData();      // Raw service data bytes

    GatewayLocation loc  = result.getLocation();
    double lat           = loc.getLatitude();
    double lng           = loc.getLongitude();
    float accuracy       = loc.getHorizontalAccuracyMeters();
};

// Register in onResume
HubbleGatewaySDK.addScanListener(scanListener);

// Unregister in onPause to avoid leaks
HubbleGatewaySDK.removeScanListener(scanListener);
```

The listener is called on the main thread each time a device is scanned. Add the listener in `onResume()` and remove it in `onPause()` to match the activity lifecycle.

### 7. Active scanning for debugging

For immediate scan results during development, use the active scanning API. This forces the foreground service to run at the movement-detected scan interval, regardless of actual device movement:

```java
// Start active scanning (foreground use only)
HubbleGatewaySDK.startActiveScanning();

// Stop when done
HubbleGatewaySDK.stopActiveScanning();

// Check state
boolean active = HubbleGatewaySDK.isActiveScanning();
```

Active scanning requires background scanning to be started and a `NotificationConfig` to be set. It works in both full mode (BLE + location) and location-only mode. It scans in periodic bursts (30 seconds every ~5 minutes) using the same mechanism as movement-triggered scanning.

### 8. Stop and resume scanning

```java
// Pause background scanning (SDK stays initialized, queued results are retained)
HubbleGatewaySDK.stopScanning();

// Resume background scanning
HubbleGatewaySDK.startScanning();

// Check current state
boolean scanning = HubbleGatewaySDK.isScanning();
```

## Device requirements

- **Location services** must be enabled (the SDK uses the fused location provider via Google Play Services when available, and automatically falls back to the platform `LocationManager` on devices without Play Services).
- **Bluetooth LE** is recommended for full functionality (BLE device scanning). Without BLE support or permissions, the SDK operates in location-only mode, collecting device locations without scanning for nearby Hubble devices.

