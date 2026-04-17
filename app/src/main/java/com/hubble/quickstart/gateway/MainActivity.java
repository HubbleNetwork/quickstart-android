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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final long REFRESH_INTERVAL_MS = 1000;

    private ActivityMainBinding binding;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private boolean isRefreshing = false;

    /** Deduped scan results keyed by device address, keeping only the most recent per device. */
    private final Map<String, GatewayScanResult> scanResultsByDevice = new LinkedHashMap<>();

    private final GatewayScanListener scanListener = result -> {
        scanResultsByDevice.put(result.getDeviceAddress(), result);
        refreshHandler.post(this::refreshScanResults);
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
        stopRefreshing();
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
                HubbleGatewaySDK.isScanning() ? "Active" : "Inactive"));
        binding.textActiveScanState.setText(getString(R.string.label_active_scan_state,
                HubbleGatewaySDK.isActiveScanning() ? "Active" : "Inactive"));
        binding.textLocationCollectingState.setText(getString(
                R.string.label_location_collecting_state,
                HubbleGatewaySDK.isLocationCollecting() ? "Active" : "Inactive"));
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

        boolean fullyOp = SdkCapabilities.isFullyOperational(this);
        boolean minOp = SdkCapabilities.isMinimallyOperational(this);
        String modeStatus = fullyOp ? "Full (BLE + Location)"
                : minOp ? "Location Only" : "Not Operational";
        binding.textOperationalMode.setText(getString(R.string.label_operational_mode, modeStatus));
    }

    private void refreshScanResults() {
        List<GatewayScanResult> results = new ArrayList<>(scanResultsByDevice.values());
        // Most recent first
        Collections.sort(results,
                (a, b) -> Long.compare(b.getTimestampMillis(), a.getTimestampMillis()));

        binding.textScanResultsLabel.setText(getString(R.string.label_scan_results, results.size()));
        binding.textNoResults.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
        binding.containerScanResults.setVisibility(results.isEmpty() ? View.GONE : View.VISIBLE);

        binding.containerScanResults.removeAllViews();
        for (GatewayScanResult result : results) {
            ItemScanResultBinding item = ItemScanResultBinding.inflate(
                    getLayoutInflater(), binding.containerScanResults, false);
            bindScanResult(item, result);
            binding.containerScanResults.addView(item.getRoot());
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
            StringBuilder sb = new StringBuilder(data.length * 2);
            for (byte b : data) sb.append(String.format(Locale.US, "%02X", b));
            item.textServiceData.setText(getString(R.string.label_service_data, sb.toString()));
        } else {
            item.textServiceData.setText(R.string.label_service_data_none);
        }

        GatewayLocation location = result.getLocation();
        item.textLocation.setText(getString(R.string.label_location,
                location.getLatitude(), location.getLongitude(),
                location.getHorizontalAccuracyMeters()));

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
