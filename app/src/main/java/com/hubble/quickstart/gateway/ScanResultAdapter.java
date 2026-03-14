package com.hubble.quickstart.gateway;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import android.os.ParcelUuid;

import com.hubble.quickstart.gateway.databinding.ItemScanResultBinding;
import com.hubble.sdk.gateway.GatewayLocation;
import com.hubble.sdk.gateway.GatewayScanResult;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ScanResultAdapter extends RecyclerView.Adapter<ScanResultAdapter.ViewHolder> {

    private List<GatewayScanResult> results = new ArrayList<>();
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    public void updateResults(List<GatewayScanResult> newResults) {
        this.results = new ArrayList<>(newResults);
        Collections.reverse(this.results);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemScanResultBinding binding = ItemScanResultBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(results.get(position));
    }

    @Override
    public int getItemCount() {
        return results.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemScanResultBinding binding;

        ViewHolder(ItemScanResultBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(GatewayScanResult result) {
            binding.textDeviceAddress.setText(
                    itemView.getContext().getString(R.string.label_device_address,
                            result.getDeviceAddress()));
            binding.textRssi.setText(
                    itemView.getContext().getString(R.string.label_rssi, result.getRssi()));
            binding.textTimestamp.setText(
                    itemView.getContext().getString(R.string.label_timestamp,
                            timeFormat.format(new Date(result.getTimestampMillis()))));

            ParcelUuid uuid = result.getServiceUuid();
            binding.textServiceUuid.setText(
                    itemView.getContext().getString(R.string.label_service_uuid,
                            uuid != null ? formatUuidShort(uuid) : "unknown"));

            byte[] data = result.getServiceData();
            if (data != null) {
                binding.textServiceData.setText(
                        itemView.getContext().getString(R.string.label_service_data,
                                bytesToHex(data)));
            } else {
                binding.textServiceData.setText(R.string.label_service_data_none);
            }

            GatewayLocation location = result.getLocation();
            binding.textLocation.setText(
                    itemView.getContext().getString(R.string.label_location,
                            location.getLatitude(),
                            location.getLongitude(),
                            location.getHorizontalAccuracyMeters()));

            // Extended location fields
            Double altitude = location.getAltitude();
            Float verticalAccuracy = location.getVerticalAccuracyMeters();
            if (altitude != null) {
                float vAcc = verticalAccuracy != null ? verticalAccuracy : 0f;
                binding.textAltitude.setText(
                        itemView.getContext().getString(R.string.label_altitude,
                                altitude.floatValue(), vAcc));
                binding.textAltitude.setVisibility(View.VISIBLE);
            } else {
                binding.textAltitude.setVisibility(View.GONE);
            }

            Float bearing = location.getBearing();
            Float bearingAccuracy = location.getBearingAccuracyDegrees();
            if (bearing != null) {
                float bAcc = bearingAccuracy != null ? bearingAccuracy : 0f;
                binding.textBearing.setText(
                        itemView.getContext().getString(R.string.label_bearing, bearing, bAcc));
                binding.textBearing.setVisibility(View.VISIBLE);
            } else {
                binding.textBearing.setVisibility(View.GONE);
            }

            Float speed = location.getSpeed();
            Float speedAccuracy = location.getSpeedAccuracyMetersPerSecond();
            if (speed != null) {
                float sAcc = speedAccuracy != null ? speedAccuracy : 0f;
                binding.textSpeed.setText(
                        itemView.getContext().getString(R.string.label_speed, speed, sAcc));
                binding.textSpeed.setVisibility(View.VISIBLE);
            } else {
                binding.textSpeed.setVisibility(View.GONE);
            }
        }
    }

    private static String formatUuidShort(ParcelUuid uuid) {
        String hex = uuid.getUuid().toString().substring(4, 8).toUpperCase(Locale.US);
        return "0x" + hex;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02X", b));
        }
        return sb.toString();
    }
}
