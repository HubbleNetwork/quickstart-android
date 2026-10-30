package com.hubble.quickstart.gateway;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.hubble.quickstart.gateway.databinding.ActivityMainBinding;
import com.hubble.quickstart.gateway.databinding.ItemScanResultBinding;
import com.hubble.sdk.gateway.GatewayLocation;
import com.hubble.sdk.gateway.GatewayScanListener;
import com.hubble.sdk.gateway.GatewayScanResult;
import com.hubble.sdk.gateway.HubbleGatewaySDK;
import com.hubble.sdk.gateway.SdkCapabilities;
import com.hubble.sdk.gateway.UploadDiagnostics;

import android.os.ParcelUuid;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final long REFRESH_INTERVAL_MS = 1000;

    /**
     * How often the sighting list is redrawn, at most.
     *
     * The scan listener fires once per advertisement. Under {@code startScanning()} that is a
     * batched trickle, but a foreground scan delivers continuously at low latency, and redrawing
     * per sighting would peg the main thread. Coalescing to four frames a second is more than the
     * eye needs.
     */
    private static final long RENDER_INTERVAL_MS = 250;

    /**
     * How long a sighting stays on screen after it was last seen.
     *
     * The SDK reports that a device was seen and never that it stopped being seen, so entries have
     * to age out here or the list only ever grows.
     */
    private static final long ENTRY_TTL_MS = 30_000;

    /** Upper bound on rows actually inflated, however many entries are live. */
    private static final int MAX_VISIBLE_RESULTS = 50;

    private ActivityMainBinding binding;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private boolean isRefreshing = false;

    /**
     * Deduped sightings, keyed by service UUID and advertised payload.
     *
     * Deliberately <em>not</em> keyed by device address: Hubble beacons rotate their address, so an
     * address-keyed map shows one tag as a stream of new devices and grows without bound. A
     * {@link ConcurrentHashMap} because the SDK delivers sightings on a binder thread while the
     * main thread reads this to draw.
     */
    private final Map<String, GatewayScanResult> scanResultsByPayload = new ConcurrentHashMap<>();

    /** Set while a redraw is already queued, so a burst of sightings costs one frame, not N. */
    private final AtomicBoolean renderScheduled = new AtomicBoolean(false);

    private final Runnable renderRunnable = () -> {
        renderScheduled.set(false);
        refreshScanResults();
    };

    private final GatewayScanListener scanListener = result -> {
        scanResultsByPayload.put(payloadKey(result), result);
        scheduleRender();
    };

    private ActivityResultLauncher<String[]> permissionLauncher;

    /**
     * Permission groups still waiting to be requested, populated from
     * {@link SdkCapabilities#getMissingPermissionGroups}. Each inner list is a batch that can be
     * requested together; batches must be requested sequentially (e.g. foreground location before
     * background location).
     */
    private List<List<String>> pendingPermissionGroups;

    /**
     * Guards against launching the permission launcher while a request is already in flight.
     * {@link ActivityResultLauncher#launch} throws if called before the previous result returns.
     */
    private boolean isRequestingPermissions = false;

    /**
     * Set to true once we've completed a full permission request cycle. Prevents
     * re-prompting on every onResume() when the user has already denied permissions.
     */
    private boolean hasCompletedPermissionFlow = false;

    /**
     * Snapshot of missing permissions when the last permission flow completed. If the set
     * changes on a subsequent onResume() (e.g. user granted a permission in Settings),
     * the flow is allowed to run again.
     */
    private List<String> lastMissingPermissions = new ArrayList<>();


    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatusDisplay();
            updateCapabilitiesDisplay();
            // Both polled, not pushed: the SDK stops the foreground scan by itself when the
            // app leaves the foreground or the adapter goes down, with no callback to say so, and
            // a start it refuses is worth retrying (see ensureForegroundScanning).
            ensureForegroundScanning();
            updateForegroundScanState();
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Ensure dark status bar icons so they're visible over the light app background
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowInsetsController insetsController = getWindow().getInsetsController();
            if (insetsController != null) {
                insetsController.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        // Add top padding for the status bar so content doesn't draw behind it
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentLayout, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            int basePadding = (int) (16 * getResources().getDisplayMetrics().density);
            v.setPadding(basePadding, basePadding + insets.top, basePadding, basePadding + insets.bottom);
            return windowInsets;
        });

        // SDK version
        binding.textSdkVersion.setText(getString(R.string.label_sdk_version,
                HubbleGatewaySDK.VERSION));


        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::onPermissionResults
        );

        // Copy gateway ID to clipboard on status card tap
        binding.cardSdkStatus.setOnClickListener(v -> {
            UploadDiagnostics diagnostics = HubbleGatewaySDK.getUploadDiagnostics();
            String id = diagnostics != null ? diagnostics.getGatewayId() : null;
            if (id != null && !id.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Gateway ID", id));
                Toast.makeText(this, R.string.toast_gateway_id_copied, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.toast_gateway_id_not_registered, Toast.LENGTH_SHORT).show();
            }
        });

        // Active scan controls
        binding.btnStartActive.setOnClickListener(v -> onStartActiveClicked());
        binding.btnStopActive.setOnClickListener(v -> onStopActiveClicked());

        updateStatusDisplay();
        updateCapabilitiesDisplay();
        updateActiveButtonStates();
        updateForegroundScanState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HubbleGatewaySDK.addScanListener(scanListener);
        startRefreshing();
        ensureScanning();
    }

    @Override
    protected void onPause() {
        super.onPause();
        HubbleGatewaySDK.removeScanListener(scanListener);
        // The SDK stops the foreground scan on backgrounding anyway; doing it here keeps the
        // button states honest and makes the pairing obvious to anyone reading this sample.
        HubbleGatewaySDK.stopForegroundScanning();
        stopRefreshing();
        refreshHandler.removeCallbacks(renderRunnable);
        renderScheduled.set(false);
    }

    // --- Automatic scanning with permission flow ---

    /**
     * Ensures scanning is active. Called on every {@link #onResume()} and after permission grants.
     * If already scanning, this is a no-op. If permissions are missing, kicks off the sequential
     * permission request flow — scanning starts automatically once all required permissions are
     * granted.
     */
    private void ensureScanning() {
        if (HubbleGatewaySDK.isScanning()) {
            return;
        }

        // getMissingPermissionGroups returns permissions pre-grouped in the correct request order.
        // Group 1: foreground permissions (FINE_LOCATION, BLUETOOTH_SCAN, POST_NOTIFICATIONS)
        // Group 2: ACCESS_BACKGROUND_LOCATION (must be requested separately on API 30+)
        // Group 3: optional permissions (ACTIVITY_RECOGNITION)
        pendingPermissionGroups = SdkCapabilities.getMissingPermissionGroups(this);

        if (pendingPermissionGroups.isEmpty()) {
            startScanning();
            return;
        }

        // If the set of missing permissions changed since the last flow (e.g. user granted
        // something in Settings), allow the flow to run again.
        List<String> currentMissing = SdkCapabilities.getMissingPermissions(this);
        if (hasCompletedPermissionFlow && !currentMissing.equals(lastMissingPermissions)) {
            hasCompletedPermissionFlow = false;
        }

        if (!isRequestingPermissions && !hasCompletedPermissionFlow) {
            requestNextPermissionGroup();
        }
    }

    private void onStartActiveClicked() {
        if (SdkCapabilities.isMinimallyOperational(this)) {
            HubbleGatewaySDK.startActiveScanning();
            updateActiveButtonStates();
            updateStatusDisplay();
        } else {
            // Reset the permission flow so ensureScanning() will re-request missing permissions
            // (e.g. background location that was denied or skipped on the first pass).
            hasCompletedPermissionFlow = false;
            ensureScanning();
        }
    }

    private void onStopActiveClicked() {
        HubbleGatewaySDK.stopActiveScanning();
        updateActiveButtonStates();
        updateStatusDisplay();
    }

    /**
     * Requests the next batch from {@link #pendingPermissionGroups}, showing a rationale dialog
     * first if needed. When all groups have been requested, starts scanning.
     */
    private void requestNextPermissionGroup() {
        if (pendingPermissionGroups == null || pendingPermissionGroups.isEmpty()) {
            isRequestingPermissions = false;
            hasCompletedPermissionFlow = true;
            lastMissingPermissions = SdkCapabilities.getMissingPermissions(this);
            // All groups requested — start scanning if at least minimally operational
            // (location-only mode). BLE scanning will activate if BLE permissions are granted.
            if (SdkCapabilities.isMinimallyOperational(this)) {
                startScanning();
            } else {
                Toast.makeText(this, R.string.toast_permissions_required,
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }

        isRequestingPermissions = true;
        List<String> group = pendingPermissionGroups.remove(0);

        boolean shouldShowRationale = false;
        for (String perm : group) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                shouldShowRationale = true;
                break;
            }
        }

        // Check if this group is ACCESS_BACKGROUND_LOCATION alone. The SDK's
        // getMissingPermissionGroups() guarantees it is in its own group (requiresSeparateRequest).
        boolean isBackgroundGroup = group.size() == 1
                && "android.permission.ACCESS_BACKGROUND_LOCATION".equals(group.get(0));

        int rationaleMessage = isBackgroundGroup
                ? R.string.permission_rationale_background_location
                : R.string.permission_rationale_message;

        // Always show rationale for background location (user needs context for the "Allow all the
        // time" choice). For other groups, only show if the system asks us to. When the user
        // confirms, calling permissionLauncher.launch() on API 30+ takes them directly to the
        // location permission settings screen — not the generic app settings — so they can
        // select "Allow all the time" with one tap.
        if (shouldShowRationale || isBackgroundGroup) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permission_rationale_title)
                    .setMessage(rationaleMessage)
                    .setPositiveButton(android.R.string.ok, (dialog, which) ->
                            permissionLauncher.launch(group.toArray(new String[0])))
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                        // Skip this group and continue to the next
                        requestNextPermissionGroup();
                    })
                    .show();
        } else {
            permissionLauncher.launch(group.toArray(new String[0]));
        }
    }

    private void onPermissionResults(Map<String, Boolean> results) {
        updateCapabilitiesDisplay();
        // Continue requesting the next group regardless of whether this group was fully granted.
        // The final check in requestNextPermissionGroup will verify required permissions.
        requestNextPermissionGroup();
    }

    private void startScanning() {
        HubbleGatewaySDK.startScanning();
        updateStatusDisplay();
    }

    // --- UI updates ---

    /**
     * Keeps the continuous scan running for as long as this screen is.
     *
     * Called from the one-second tick rather than once in {@code onResume}, because the SDK can
     * refuse a start that is right to make. It applies a short cool-down between starts — the
     * platform downgrades an app that starts scans too often — and the SDK stops the scan by itself
     * the moment the app is backgrounded. A rotation is therefore a stop and a start within a
     * second or two, and the start lands inside that cool-down and is refused. Retrying on the next
     * tick rides it out; a single attempt in {@code onResume} would leave the screen dead after
     * every rotation, with nothing on screen to explain why.
     *
     * Gated on the conditions a host can see, so a device with Bluetooth off or permissions missing
     * does not ask once a second and fill the log with refusals.
     */
    private void ensureForegroundScanning() {
        if (HubbleGatewaySDK.isForegroundScanning()) return;
        if (!SdkCapabilities.isBluetoothEnabled(this)) return;
        // Exactly what this scan needs, and nothing more. NOT getMissingPermissions(), which is
        // the set the background paths need — it includes ACCESS_BACKGROUND_LOCATION and
        // POST_NOTIFICATIONS. Gating on that set would refuse to even try on a device whose user
        // declined background location, which is the very case this scan exists for: a visible
        // screen, no account, no reason to have been asked for either.
        if (!SdkCapabilities.isLocationPermissionGranted(this)) return;
        if (!SdkCapabilities.hasBlePermissions(this)) return;
        // listenersOnly = false: the continuous scan contributes to the network as well as to the
        // screen. The device already has the sightings and a location for them, so discarding them
        // wastes coverage the user is already paying battery for.
        //
        // What is uploaded does not widen: enqueueOrNotify drops anything with no matching service
        // UUID and routes host scan-only UUIDs to listeners regardless of this flag, so only
        // payload services (FCA6 plus the SDK's payload list) are ever stored.
        //
        // While this runs the movement foreground service holds off its own bursts, since a
        // continuous low-latency scan already stores everything a burst would.
        HubbleGatewaySDK.startForegroundScanning(false);
    }

    private void updateForegroundScanState() {
        boolean scanning = HubbleGatewaySDK.isForegroundScanning();
        binding.textForegroundScanState.setText(getString(
                R.string.label_foreground_scan_state, scanning ? "Active" : "Inactive"));
    }

    private void scheduleRender() {
        if (renderScheduled.compareAndSet(false, true)) {
            refreshHandler.postDelayed(renderRunnable, RENDER_INTERVAL_MS);
        }
    }

    /**
     * Dedup key: the advertised payload, not the device address.
     *
     * Two sightings of the same tag carry the same service data under the same service UUID even
     * when the address has rotated between them.
     */
    private static String payloadKey(GatewayScanResult result) {
        ParcelUuid uuid = result.getServiceUuid();
        return (uuid != null ? uuid.toString() : "unknown") + ':' + toHex(result.getServiceData());
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02X", b));
        }
        return sb.toString();
    }

    private void updateActiveButtonStates() {
        boolean active = HubbleGatewaySDK.isActiveScanning();
        binding.btnStartActive.setEnabled(!active);
        binding.btnStopActive.setEnabled(active);
    }

    private void updateStatusDisplay() {
        binding.textInitState.setText(getString(R.string.label_init_state,
                HubbleGatewaySDK.getInitializationState().toString()));

        UploadDiagnostics diagnostics = HubbleGatewaySDK.getUploadDiagnostics();
        String gatewayId = diagnostics != null ? diagnostics.getGatewayId() : null;
        binding.textGatewayId.setText(getString(R.string.label_gateway_id,
                gatewayId != null && !gatewayId.isEmpty() ? gatewayId : "Unregistered"));

        binding.textScanningState.setText(getString(R.string.label_scanning_state,
                backgroundScanState()));
        binding.textActiveScanState.setText(getString(R.string.label_active_scan_state,
                HubbleGatewaySDK.isActiveScanning() ? "Active" : "Inactive"));
        binding.textLocationCollectingState.setText(getString(
                R.string.label_location_collecting_state,
                HubbleGatewaySDK.isLocationCollecting() ? "Active" : "Inactive"));
    }

    /**
     * Describes how much of the BLE scanning is running, and why it is not all of it.
     *
     * Without ACCESS_BACKGROUND_LOCATION the SDK refuses the always-on scan, because that one
     * delivers results while the app is in the background. The continuous foreground scan needs
     * neither background location nor notifications and runs regardless, so on such a device BLE
     * scanning is not off — it is foreground-only, and saying "Inactive" beside a device list that
     * is visibly filling up would be wrong. The live state of that scan is the Foreground Scan
     * line; this one is about what the permissions allow.
     */
    private String backgroundScanState() {
        if (HubbleGatewaySDK.isScanning()) {
            return "Active";
        }
        if (!SdkCapabilities.hasBlePermissions(this)) {
            return "Inactive — needs Bluetooth permission";
        }
        if (!SdkCapabilities.isBackgroundLocationPermissionGranted(this)) {
            return "Foreground only — missing background location permission";
        }
        return "Inactive";
    }

    private void updateCapabilitiesDisplay() {
        binding.textBleSupported.setText(getString(R.string.label_ble_supported,
                SdkCapabilities.isBleSupported(this) ? "Supported" : "Not Available"));
        binding.textBluetoothEnabled.setText(getString(R.string.label_bluetooth_enabled,
                SdkCapabilities.isBluetoothEnabled(this) ? "Enabled" : "Disabled"));
        binding.textPlayServices.setText(getString(R.string.label_play_services,
                SdkCapabilities.isPlayServicesAvailable(this) ? "Available" : "Unavailable"));

        List<String> missingRequired = SdkCapabilities.getMissingPermissions(this);
        List<String> missingOptional = SdkCapabilities.getMissingOptionalPermissions(this);
        String permStatus;
        if (missingRequired.isEmpty() && missingOptional.isEmpty()) {
            permStatus = "All Granted";
        } else if (missingRequired.isEmpty()) {
            permStatus = missingOptional.size() + " optional missing";
        } else {
            permStatus = missingRequired.size() + " required missing";
        }
        binding.textPermissions.setText(getString(R.string.label_permissions, permStatus));

        binding.textLocationPermissions.setText(getString(R.string.label_location_permissions,
                SdkCapabilities.hasLocationPermissions(this) ? "Granted" : "Not Granted"));
        binding.textBlePermissions.setText(getString(R.string.label_ble_permissions,
                SdkCapabilities.hasBlePermissions(this) ? "Granted" : "Not Granted"));

        binding.textBackgroundLocation.setText(getString(R.string.label_background_location,
                SdkCapabilities.isBackgroundLocationPermissionGranted(this)
                        ? "Granted" : "Not Granted"));

        binding.textOperationalMode.setText(
                getString(R.string.label_operational_mode, operationalMode()));
    }

    /**
     * What this device can currently do, in the sample's own words.
     *
     * Both SDK predicates fold ACCESS_BACKGROUND_LOCATION in, so without it they are false and a
     * naive reading prints "Not Operational" for a device that is visibly scanning and uploading
     * locations. The foreground case is reported separately here; the SDK has no state for it.
     */
    private String operationalMode() {
        if (SdkCapabilities.isFullyOperational(this)) {
            return "Full (BLE + Location)";
        }
        if (SdkCapabilities.isMinimallyOperational(this)) {
            return "Location Only";
        }
        boolean foregroundCapable = SdkCapabilities.isLocationPermissionGranted(this);
        if (foregroundCapable && SdkCapabilities.hasBlePermissions(this)) {
            return "Foreground Only (BLE + Location)";
        }
        if (foregroundCapable) {
            return "Foreground Only (Location)";
        }
        return "Not Operational";
    }

    private void refreshScanResults() {
        pruneStaleResults();

        List<GatewayScanResult> results = new ArrayList<>(scanResultsByPayload.values());
        // Most recent first
        Collections.sort(results,
                (a, b) -> Long.compare(b.getTimestampMillis(), a.getTimestampMillis()));

        binding.textScanResultsLabel.setText(getString(R.string.label_scan_results, results.size()));
        binding.textNoResults.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
        binding.containerScanResults.setVisibility(results.isEmpty() ? View.GONE : View.VISIBLE);

        int visible = Math.min(results.size(), MAX_VISIBLE_RESULTS);

        // Rows are rebound, not rebuilt. Inflating the whole list on every redraw is what makes a
        // continuous scan unusable: it is main-thread work proportional to the list, at the rate
        // advertisements arrive.
        while (binding.containerScanResults.getChildCount() > visible) {
            binding.containerScanResults.removeViewAt(
                    binding.containerScanResults.getChildCount() - 1);
        }
        for (int i = 0; i < visible; i++) {
            ItemScanResultBinding item;
            if (i < binding.containerScanResults.getChildCount()) {
                item = ItemScanResultBinding.bind(binding.containerScanResults.getChildAt(i));
            } else {
                item = ItemScanResultBinding.inflate(
                        getLayoutInflater(), binding.containerScanResults, false);
                binding.containerScanResults.addView(item.getRoot());
            }
            bindScanResult(item, results.get(i));
        }
    }

    /**
     * Drops entries not seen for {@link #ENTRY_TTL_MS}.
     *
     * The SDK never reports that a device went away, so without this the list is a record of
     * everything ever seen rather than of what is nearby.
     */
    private void pruneStaleResults() {
        long cutoff = System.currentTimeMillis() - ENTRY_TTL_MS;
        Iterator<GatewayScanResult> it = scanResultsByPayload.values().iterator();
        while (it.hasNext()) {
            if (it.next().getTimestampMillis() < cutoff) {
                it.remove();
            }
        }
    }

    private void bindScanResult(ItemScanResultBinding item, GatewayScanResult result) {
        item.textDeviceAddress.setText(getString(R.string.label_device_address,
                result.getDeviceAddress()));
        item.textRssi.setText(getString(R.string.label_rssi, result.getRssi()));
        item.textTimestamp.setText(getString(R.string.label_timestamp,
                timeFormat.format(new Date(result.getTimestampMillis()))));

        ParcelUuid uuid = result.getServiceUuid();
        String uuidHex = uuid != null
                ? "0x" + uuid.getUuid().toString().substring(4, 8).toUpperCase(Locale.US)
                : "unknown";
        item.textServiceUuid.setText(getString(R.string.label_service_uuid, uuidHex));

        byte[] data = result.getServiceData();
        if (data != null) {
            item.textServiceData.setText(getString(R.string.label_service_data, toHex(data)));
        } else {
            item.textServiceData.setText(R.string.label_service_data_none);
        }

        GatewayLocation location = result.getLocation();
        // A sighting always carries a location, so when the SDK has no fix it carries the
        // unknown-location sentinel instead. 0.0, 0.0 is a real point in the Gulf of Guinea, so
        // anything that plots, geocodes or distance-filters a sighting has to check this first.
        if (location.isUnknown()) {
            item.textLocation.setText(R.string.label_location_unknown);
        } else {
            item.textLocation.setText(getString(R.string.label_location,
                    location.getLatitude(), location.getLongitude(),
                    location.getHorizontalAccuracyMeters()));
        }

        Double altitude = location.getAltitude();
        Float verticalAccuracy = location.getVerticalAccuracyMeters();
        if (altitude != null) {
            float vAcc = verticalAccuracy != null ? verticalAccuracy : 0f;
            item.textAltitude.setText(getString(R.string.label_altitude,
                    altitude.floatValue(), vAcc));
            item.textAltitude.setVisibility(View.VISIBLE);
        } else {
            item.textAltitude.setVisibility(View.GONE);
        }

        Float bearing = location.getBearing();
        Float bearingAccuracy = location.getBearingAccuracyDegrees();
        if (bearing != null) {
            float bAcc = bearingAccuracy != null ? bearingAccuracy : 0f;
            item.textBearing.setText(getString(R.string.label_bearing, bearing, bAcc));
            item.textBearing.setVisibility(View.VISIBLE);
        } else {
            item.textBearing.setVisibility(View.GONE);
        }

        Float speed = location.getSpeed();
        Float speedAccuracy = location.getSpeedAccuracyMetersPerSecond();
        if (speed != null) {
            float sAcc = speedAccuracy != null ? speedAccuracy : 0f;
            item.textSpeed.setText(getString(R.string.label_speed, speed, sAcc));
            item.textSpeed.setVisibility(View.VISIBLE);
        } else {
            item.textSpeed.setVisibility(View.GONE);
        }
    }

    private void startRefreshing() {
        if (!isRefreshing) {
            isRefreshing = true;
            refreshHandler.post(refreshRunnable);
        }
    }

    private void stopRefreshing() {
        isRefreshing = false;
        refreshHandler.removeCallbacks(refreshRunnable);
    }
}
