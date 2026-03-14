package com.hubble.quickstart.gateway;

import android.app.Application;
import com.hubble.sdk.gateway.HubbleGatewayConfig;
import com.hubble.sdk.gateway.HubbleGatewaySDK;
import com.hubble.sdk.gateway.NotificationConfig;

public class QuickstartApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

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
}
