package app.organicmaps.nogps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;
import app.organicmaps.sdk.location.MockLocationShare;
import app.organicmaps.sdk.location.NoGps;
import app.organicmaps.sdk.util.log.Logger;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The inertial navigation: its switch and the ELM327 adapter are shown at once, whatever the state, then the speed,
 * the gyroscope and GPS. Choosing the adapter turns the inertial navigation on.
 */
public class SensorsBottomSheet extends BottomSheetDialogFragment
{
  public static final String TAG = SensorsBottomSheet.class.getSimpleName();
  // The fragment result told when the user closes the sheet.
  public static final String RESULT_CLOSED = TAG + ".closed";
  private static final long UPDATE_INTERVAL_MS = 500;
  private static final String STATE_SHARE_ASKED = "share_asked";

  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final Runnable mUpdater = new Runnable() {
    @Override
    public void run()
    {
      update();
      mHandler.postDelayed(this, UPDATE_INTERVAL_MS);
    }
  };

  private final ActivityResultLauncher<String> mBluetoothPermissionLauncher =
      registerForActivityResult(new ActivityResultContracts.RequestPermission(), this::onBluetoothPermissionResult);
  // What to do after the Bluetooth permission is granted.
  @Nullable
  private Runnable mAfterPermission;
  // The user has turned the share on before the app is picked as the mock location app.
  private boolean mShareAsked;

  private SwitchCompat mInertial;
  private View mDetails;
  private TextView mAdapter;
  private TextView mSpeed;
  private TextView mScale;
  private TextView mRoughSpeed;
  private TextView mSpeedTableToggle;
  private LinearLayout mSpeedTable;
  // Not saved: the sheet opens with the table folded.
  private boolean mSpeedTableShown;
  private TextView mLag;
  private TextView mGyro;
  private TextView mReadiness;
  private TextView mCalibrate;
  private GpsSheetView mGpsView;
  private SwitchCompat mGpsUse;
  private View mGpsUseHint;
  private View mGpsUseNeedsAdapter;
  private SwitchCompat mSharePosition;
  private View mSharePositionNeedsMock;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    if (savedInstanceState != null)
      mShareAsked = savedInstanceState.getBoolean(STATE_SHARE_ASKED);
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState)
  {
    super.onSaveInstanceState(outState);
    outState.putBoolean(STATE_SHARE_ASKED, mShareAsked);
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState)
  {
    final View view = inflater.inflate(R.layout.nogps_sensors_sheet, container, false);
    mInertial = view.findViewById(R.id.nogps_inertial);
    mInertial.setOnClickListener(v -> onInertialSwitched(mInertial.isChecked()));
    // Without the gyroscope the car can't be turned: only the marks of the user move it.
    final boolean gyroscope = hasGyroscope(requireContext());
    view.findViewById(R.id.nogps_no_gyro).setVisibility(gyroscope ? View.GONE : View.VISIBLE);
    mDetails = view.findViewById(R.id.nogps_details);
    mAdapter = view.findViewById(R.id.nogps_adapter);
    view.findViewById(R.id.nogps_choose_adapter).setOnClickListener(v -> withBluetoothPermission(this::chooseAdapter));
    mSpeed = view.findViewById(R.id.nogps_speed);
    mScale = view.findViewById(R.id.nogps_scale);
    mRoughSpeed = view.findViewById(R.id.nogps_rough_speed);
    mSpeedTableToggle = view.findViewById(R.id.nogps_speed_table_toggle);
    mSpeedTable = view.findViewById(R.id.nogps_speed_table);
    mSpeedTableToggle.setOnClickListener(v -> {
      mSpeedTableShown = !mSpeedTableShown;
      update();
    });
    mLag = view.findViewById(R.id.nogps_lag);
    view.findViewById(R.id.nogps_clear_speed)
        .setOnClickListener(v -> confirmClearSpeed(requireContext(), this::update));
    mGyro = view.findViewById(R.id.nogps_gyro);
    mReadiness = view.findViewById(R.id.nogps_readiness);
    mCalibrate = view.findViewById(R.id.nogps_calibrate);
    mCalibrate.setOnClickListener(v -> NoGps.nativeCalibrate());
    mCalibrate.setEnabled(gyroscope);

    mGpsView = view.findViewById(R.id.nogps_gps_view);
    mGpsUse = view.findViewById(R.id.nogps_gps_use);
    mGpsUseHint = view.findViewById(R.id.nogps_gps_use_hint);
    mGpsUseNeedsAdapter = view.findViewById(R.id.nogps_gps_use_needs_adapter);
    mGpsUse.setOnClickListener(v -> {
      NoGps.nativeSetGpsDisabled(!mGpsUse.isChecked());
      update();
    });
    mSharePosition = view.findViewById(R.id.nogps_share_position);
    mSharePositionNeedsMock = view.findViewById(R.id.nogps_share_position_needs_mock);
    mSharePosition.setOnClickListener(v -> {
      final boolean share = mSharePosition.isChecked();
      mShareAsked = share && !MockLocationShare.isAllowed(requireContext());
      MockLocationShare.setEnabled(share && !mShareAsked);
      // The share is turned on when the user comes back with the app picked there.
      if (mShareAsked)
        openDeveloperOptions();
      update();
    });
    mSharePositionNeedsMock.setOnClickListener(v -> openDeveloperOptions());
    return view;
  }

  @Override
  public void onStart()
  {
    super.onStart();
    mHandler.post(mUpdater);
  }

  @Override
  public void onStop()
  {
    super.onStop();
    mHandler.removeCallbacks(mUpdater);
    mGpsView.stop();
  }

  /**
   * Turned on without an adapter, the inertial navigation asks for one: nothing tells the speed of the car otherwise.
   */
  private void onInertialSwitched(boolean enabled)
  {
    Logger.i(TAG, "The inertial navigation is turned " + (enabled ? "on" : "off") + " by the user");
    NoGps.nativeSetInertialNavigationEnabled(enabled);
    if (enabled && NoGps.getStatus().elm327Address.isEmpty())
      withBluetoothPermission(this::chooseAdapter);
    update();
  }

  /**
   * The phone turns the car by the gyroscope, see MotionSensors: the uncalibrated one is taken first.
   */
  static boolean hasGyroscope(@NonNull Context context)
  {
    final SensorManager sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    if (sensors == null)
    {
      Logger.w(TAG, "No sensor service: the gyroscope is told as missing");
      return false;
    }
    return sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        || sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) != null;
  }

  private void withBluetoothPermission(@NonNull Runnable action)
  {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
               == PackageManager.PERMISSION_GRANTED)
    {
      action.run();
      return;
    }
    mAfterPermission = action;
    mBluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
  }

  private void onBluetoothPermissionResult(boolean granted)
  {
    final Runnable action = mAfterPermission;
    mAfterPermission = null;
    if (granted && action != null)
    {
      action.run();
      return;
    }
    Logger.w(TAG, "The Bluetooth permission is not granted: the adapter is not chosen");
    Toast.makeText(requireContext(), R.string.nogps_bt_permission_denied, Toast.LENGTH_LONG).show();
  }

  @SuppressLint("MissingPermission") // Checked in withBluetoothPermission().
  private void chooseAdapter()
  {
    final BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
    if (bluetooth == null)
      Logger.w(TAG, "The phone has no Bluetooth: there is no adapter to choose");
    final List<BluetoothDevice> devices =
        bluetooth == null ? new ArrayList<>() : new ArrayList<>(bluetooth.getBondedDevices());
    if (devices.isEmpty())
    {
      Toast.makeText(requireContext(), R.string.nogps_no_paired, Toast.LENGTH_LONG).show();
      return;
    }
    final String[] names = new String[devices.size()];
    for (int i = 0; i < devices.size(); i++)
    {
      final String name = devices.get(i).getName();
      names[i] = (name != null ? name : "?") + "  (" + devices.get(i).getAddress() + ")";
    }
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.nogps_choose_adapter)
        .setItems(names, (dialog, which) -> onAdapterChosen(devices.get(which).getAddress()))
        .show();
  }

  /**
   * The adapter is what the inertial navigation needs: it is turned on with the choice.
   */
  private void onAdapterChosen(@NonNull String address)
  {
    NoGps.nativeSetElm327Address(address);
    if (!NoGps.getStatus().inertialEnabled)
    {
      Logger.i(TAG, "The adapter is chosen, the inertial navigation is turned on");
      NoGps.nativeSetInertialNavigationEnabled(true);
    }
    if (getContext() != null)
      update();
  }

  @SuppressLint("MissingPermission")
  @NonNull
  private String adapterName(@NonNull NoGps.Status status)
  {
    final String address = status.elm327Address;
    if (address.isEmpty())
      return getString(R.string.nogps_sensors_adapter_none);
    final BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
    try
    {
      if (bluetooth != null)
      {
        final String name = bluetooth.getRemoteDevice(address).getName();
        if (name != null)
          return name;
      }
    }
    catch (SecurityException | IllegalArgumentException e)
    {
      // No Bluetooth permission, or a wrong saved address: the address is shown instead of the name.
      Logger.w(TAG, "The name of the adapter " + address + " is unknown", e);
    }
    return address;
  }

  private void update()
  {
    if (getContext() == null)
      return;
    final NoGps.Status status = NoGps.getStatus();
    if (!mInertial.isPressed())
      mInertial.setChecked(status.inertialEnabled);
    mDetails.setVisibility(status.inertialEnabled ? View.VISIBLE : View.GONE);

    final boolean started = status.inertialStarted;
    String adapter = adapterName(status);
    if (started)
      adapter += " · " + adapterStateText(requireContext(), status);
    mAdapter.setText(getString(R.string.nogps_sensors_adapter, adapter));

    updateGps(status);
    if (!status.inertialEnabled)
      return;

    final int speed = started ? status.speedKmh : -1;
    mSpeed.setText(getString(R.string.nogps_sensors_speed, speed >= 0 ? getString(R.string.nogps_speed_kmh, speed)
                                                                      : getString(R.string.nogps_unknown)));

    String scale = getString(
        R.string.nogps_sensors_scale,
        started ? String.format(Locale.US, "×%.2f", status.speedScale) : getString(R.string.nogps_unknown),
        started ? status.speedTableRanges : 0);
    mScale.setText(scale);
    // Until GPS measures the speed, e.g. on the first trip, it is corrected roughly by the roads.
    final boolean rough = started && status.speedTableRanges == 0;
    mRoughSpeed.setVisibility(rough ? View.VISIBLE : View.GONE);
    if (rough)
      mRoughSpeed.setText(status.roughSpeedTableRanges > 0
                              ? getString(R.string.nogps_sensors_rough_speed, status.roughSpeedTableRanges)
                              : getString(R.string.nogps_sensors_rough_speed_learning));
    updateSpeedTable(started ? status : null);
    // On its own line: next to the button it took four lines of the two the row has room for.
    mLag.setVisibility(started ? View.VISIBLE : View.GONE);
    if (started)
    {
      mLag.setText(
          getString(status.speedLagMeasured ? R.string.nogps_sensors_lag_measured : R.string.nogps_sensors_lag_usual,
                    String.format(Locale.US, "%.1f", status.speedLagSec)));
    }

    final NoGps.CalibrationState calibration = started ? status.getCalibration() : NoGps.CalibrationState.NONE;
    final String gyro = switch (calibration)
    {
      case NONE -> getString(R.string.nogps_gyro_none);
      case CALIBRATING -> getString(R.string.nogps_gyro_calibrating, status.calibrationProgress);
      case DONE -> getString(R.string.nogps_gyro_done);
      case FAILED_MOVING -> getString(R.string.nogps_gyro_failed);
    };
    mGyro.setText(getString(R.string.nogps_sensors_gyro, gyro));

    if (!started)
    {
      mReadiness.setText("");
      return;
    }
    final List<String> missing = new ArrayList<>();
    if (speed < 0)
      missing.add(getString(R.string.nogps_missing_speed));
    if (calibration != NoGps.CalibrationState.DONE)
      missing.add(getString(R.string.nogps_missing_gyro));
    if (!status.hasInertialPosition)
      missing.add(getString(R.string.nogps_missing_position));
    if (missing.isEmpty())
    {
      mReadiness.setText(R.string.nogps_sensors_ready);
      mReadiness.setTextColor(ContextCompat.getColor(requireContext(), R.color.nogps_status_gps));
    }
    else
    {
      mReadiness.setText(getString(R.string.nogps_sensors_not_ready, String.join(", ", missing)));
      mReadiness.setTextColor(ContextCompat.getColor(requireContext(), R.color.nogps_status_none));
    }
  }

  /**
   * What GPS is worth, if the car follows it, and the share of the position with other apps.
   */
  private void updateGps(@NonNull NoGps.Status status)
  {
    final Context context = requireContext();
    mGpsView.update(status);
    // GPS is turned off for the car only while the adapter can drive it: nothing would move the mark otherwise.
    final boolean canSwitch = status.gpsDisabled || isAdapterHeard(status);
    mGpsUse.setEnabled(canSwitch);
    if (!mGpsUse.isPressed())
      mGpsUse.setChecked(!status.gpsDisabled);
    mGpsUseHint.setVisibility(canSwitch ? View.VISIBLE : View.INVISIBLE);
    mGpsUseNeedsAdapter.setVisibility(canSwitch ? View.INVISIBLE : View.VISIBLE);

    // Off until the app is picked as the mock location app, the hint tells where.
    final boolean shareAllowed = MockLocationShare.isAllowed(context);
    if (mShareAsked && shareAllowed)
    {
      mShareAsked = false;
      MockLocationShare.setEnabled(true);
    }
    // Also unpicked since the share was turned on.
    final boolean shareNeedsMock = (mShareAsked || MockLocationShare.isEnabled()) && !shareAllowed;
    if (!mSharePosition.isPressed())
      mSharePosition.setChecked(MockLocationShare.isEnabled() && shareAllowed);
    mSharePositionNeedsMock.setVisibility(shareNeedsMock ? View.VISIBLE : View.GONE);
  }

  /**
   * The adapter answers: the car is followed by it, or it tells why not.
   */
  static boolean isAdapterHeard(@NonNull NoGps.Status status)
  {
    final NoGps.SourceState state = status.getSourceState();
    return status.inertialStarted && state != NoGps.SourceState.DISCONNECTED && state != NoGps.SourceState.CONNECTING;
  }

  private void openDeveloperOptions()
  {
    try
    {
      startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
    }
    catch (ActivityNotFoundException e)
    {
      // Developer options are turned on in About phone.
      Logger.w(TAG, "No Developer options, opening About phone", e);
      startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
    }
  }

  private void updateSpeedTable(@Nullable NoGps.Status status)
  {
    mSpeedTableToggle.setVisibility(status != null ? View.VISIBLE : View.GONE);
    final boolean shown = status != null && mSpeedTableShown;
    mSpeedTableToggle.setText(shown ? R.string.nogps_speed_table_hide : R.string.nogps_speed_table_show);
    mSpeedTable.setVisibility(shown ? View.VISIBLE : View.GONE);
    if (!shown)
      return;
    final int ranges = status.speedTableCarM.length;
    while (mSpeedTable.getChildCount() < ranges)
    {
      final TextView row = new TextView(requireContext());
      row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
      mSpeedTable.addView(row);
    }
    for (int i = 0; i < ranges; ++i)
      ((TextView) mSpeedTable.getChildAt(i)).setText(speedTableRowText(requireContext(), status, i));
  }

  @NonNull
  static String speedTableRowText(@NonNull Context context, @NonNull NoGps.Status status, int i)
  {
    final float carM = status.speedTableCarM[i];
    final float scale = status.speedTableScale[i];
    final String value;
    // The rough table from the roads is used while GPS has measured no range.
    if (scale > 0)
      value = String.format(Locale.US, "%s×%.2f", status.speedTableRanges == 0 ? "≈" : "", scale);
    else if (carM >= 1)
      value = context.getString(R.string.nogps_speed_table_left,
                                Math.max(1, (int) Math.ceil(NoGps.SPEED_TABLE_MIN_DISTANCE_M - carM)));
    else
      value = "—";
    return context.getString(R.string.nogps_speed_table_row, NoGps.speedTableFromKmh(i), NoGps.speedTableToKmh(i),
                             value);
  }

  static void confirmClearSpeed(@NonNull Context context, @NonNull Runnable onCleared)
  {
    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.nogps_clear_speed_title)
        .setMessage(R.string.nogps_clear_speed_message)
        .setPositiveButton(R.string.nogps_clear_speed,
                           (dialog, which) -> {
                             NoGps.nativeClearSpeedCalibration();
                             onCleared.run();
                           })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  /**
   * The state of the ELM327 adapter. Also told by the button of the bottom bar.
   */
  @NonNull
  public static String adapterStateText(@NonNull Context context, @NonNull NoGps.Status status)
  {
    return context.getString(switch (status.getSourceState())
    {
      case DISCONNECTED -> R.string.nogps_elm_disconnected;
      case CONNECTING -> R.string.nogps_elm_connecting;
      case NO_ADAPTER -> R.string.nogps_elm_no_adapter;
      case NO_CAR_DATA -> R.string.nogps_elm_no_car;
      case CONNECTED -> R.string.nogps_elm_connected;
    });
  }

  @Override
  public void onDismiss(@NonNull DialogInterface dialog)
  {
    super.onDismiss(dialog);
    // Not closed by the user when the activity is recreated.
    if (!requireActivity().isChangingConfigurations())
      getParentFragmentManager().setFragmentResult(RESULT_CLOSED, Bundle.EMPTY);
  }
}
