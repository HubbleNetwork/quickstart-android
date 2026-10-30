package com.hubble.quickstart.gateway;

import android.app.Application;
import android.util.Log;

import com.hubble.sdk.gateway.GatewayLogEntry;
import com.hubble.sdk.gateway.HubbleGatewayConfig;
import com.hubble.sdk.gateway.HubbleGatewaySDK;
import com.hubble.sdk.gateway.NotificationConfig;

public class QuickstartApplication extends Application {

    /**
     * Logcat tag for the SDK's log stream, as forwarded by this app.
     *
     * Deliberately the tag the SDK documents — `adb logcat -s HubbleGatewaySDK` is the command in
     * the SDK's own README, and a sample that does not answer it is a trap. If several Hubble apps
     * are installed they all share this tag, so add `--pid` to tell them apart.
     */
    private static final String SDK_LOG_TAG = "HubbleGatewaySDK";

    @Override
    public void onCreate() {
        super.onCreate();

        // Mirror the SDK's log stream into logcat.
        //
        // The SDK's own logcat output is gated on how the *SDK* was built, so an app depending on
        // the published release artifact never sees it. A log sink is the supported way in, and it
        // works whatever the SDK was built as. Installed before start() so the lines from
        // initialisation and the first upload cycle — registration, heartbeat, the server config
        // that came back — are not missed.
        if (BuildConfig.DEBUG) {
            HubbleGatewaySDK.setLogSink(
                    entry -> Log.println(
                            androidLevel(entry.getLevel()),
                            SDK_LOG_TAG,
                            "[" + entry.getCategory() + "] " + entry.getMessage()),
                    GatewayLogEntry.Level.DEBUG);
        }

        HubbleGatewayConfig.Builder configBuilder = new HubbleGatewayConfig.Builder()
                .respectBatterySaver(true)
                .sdkKey(BuildConfig.HUBBLE_SDK_KEY)
                .notificationConfig(
                                new NotificationConfig.Builder()
                                        .title("Hubble Quickstart")
                                        .text("Scanning for nearby Hubble devices…")
                                        .channelName("Hubble BLE Scan")
                                        .build()
                        );

        HubbleGatewaySDK.start(this, configBuilder.build());
    }

    private static int androidLevel(GatewayLogEntry.Level level) {
        switch (level) {
            case DEBUG:
                return Log.DEBUG;
            case WARN:
                return Log.WARN;
            case ERROR:
                return Log.ERROR;
            default:
                return Log.INFO;
        }
    }
}
