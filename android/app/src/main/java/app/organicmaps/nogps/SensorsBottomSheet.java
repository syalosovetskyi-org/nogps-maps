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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import app.organicmaps.BuildConfig;
import app.organicmaps.R;
import app.organicmaps.sdk.location.MockLocationShare;
import app.organicmaps.sdk.location.NoGps;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.util.Utils;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The inertial navigation: its switch and where the car movement comes from are shown at once, whatever the state:
 * the NonGPS box (Bluetooth LE, with its own gyroscope) or the ELM327 adapter with the gyroscope of the device. Under
 * the chosen one is its row: the state of the box with Connect or Set up, which opens the Box sheet, or the adapter
 * with Choose. Without a gyroscope the adapter can't turn the car: its choice is disabled and tells why, the box is
 * offered instead. Then the speed, the gyroscope and GPS. Choosing the adapter turns the inertial navigation on.
 */
public class SensorsBottomSheet extends BottomSheetDialogFragment
{
  public static final String TAG = SensorsBottomSheet.class.getSimpleName();
  // The fragment result told when the user closes the sheet.
  public static final String RESULT_CLOSED = TAG + ".closed";
  private static final long UPDATE_INTERVAL_MS = 500;
  private static final String STATE_SHARE_ASKED = "share_asked";
  private static final String SHOP_URL = "https://non-gps.com";

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
  // The state of the box tells whether it is given, see boxStateText().
  private final ActivityResultLauncher<String[]> mBoxBluetoothLauncher =
      registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
        if (result.containsValue(false))
          Logger.w(TAG, "Bluetooth for the box is not granted: " + result);
      });
  // Once for the sheet: the user may have refused.
  private boolean mBoxBluetoothAsked;
  // What to do after the Bluetooth permission is granted.
  @Nullable
  private Runnable mAfterPermission;
  // The user has turned the share on before the app is picked as the mock location app.
  private boolean mShareAsked;

  private SwitchCompat mInertial;
  private View mDetails;
  private boolean mGyroscope;
  private View mNoGyro;
  private RadioGroup mSource;
  private RadioButton mSourceBox;
  private RadioButton mSourceElm;
  private View mSourceElmNoGyro;
  private View mBoxRow;
  private TextView mBox;
  private Button mBoxOpen;
  private View mBuy;
  private View mAdapterRow;
  private TextView mAdapter;
  private TextView mHint;
  private View mFirmwareRow;
  private TextView mFirmware;
  private Button mFirmwareButton;
  private TextView mCar;
  private View mVoltageMismatch;
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
    // Without the gyroscope the device can't turn the car: the box can, it has its own.
    mGyroscope = hasGyroscope(requireContext());
    mNoGyro = view.findViewById(R.id.nogps_no_gyro);
    mDetails = view.findViewById(R.id.nogps_details);
    mSource = view.findViewById(R.id.nogps_source);
    mSourceBox = view.findViewById(R.id.nogps_source_box);
    mSourceElm = view.findViewById(R.id.nogps_source_elm);
    mSourceElmNoGyro = view.findViewById(R.id.nogps_source_elm_no_gyro);
    mSourceElm.setEnabled(mGyroscope);
    mSourceElmNoGyro.setVisibility(mGyroscope ? View.GONE : View.VISIBLE);
    mSource.check(NoGps.getStatus().esp32Source ? R.id.nogps_source_box : R.id.nogps_source_elm);
    // Set after check(): only the user's choice changes the source.
    mSource.setOnCheckedChangeListener((group, checkedId) -> onSourceChosen(checkedId));
    mBoxRow = view.findViewById(R.id.nogps_box_row);
    mBox = view.findViewById(R.id.nogps_box);
    mBoxOpen = view.findViewById(R.id.nogps_box_open);
    mBoxOpen.setOnClickListener(v -> openBoxSheet());
    mBuy = view.findViewById(R.id.nogps_buy);
    mBuy.setOnClickListener(v -> Utils.openUrl(requireContext(), SHOP_URL));
    mAdapterRow = view.findViewById(R.id.nogps_adapter_row);
    mAdapter = view.findViewById(R.id.nogps_adapter);
    view.findViewById(R.id.nogps_choose_adapter).setOnClickListener(v -> withBluetoothPermission(this::chooseAdapter));
    mHint = view.findViewById(R.id.nogps_hint);
    mFirmwareRow = view.findViewById(R.id.nogps_firmware_row);
    mFirmware = view.findViewById(R.id.nogps_firmware);
    mFirmwareButton = view.findViewById(R.id.nogps_firmware_button);
    mFirmwareButton.setOnClickListener(v -> onFirmwareButton());
    mCar = view.findViewById(R.id.nogps_car);
    mVoltageMismatch = view.findViewById(R.id.nogps_voltage_mismatch);
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
    final NoGps.Status status = NoGps.getStatus();
    if (status.inertialEnabled && status.esp32Source)
      askBoxBluetooth();
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
   * The box is searched over Bluetooth LE, it needs the permission for it.
   */
  private void onInertialSwitched(boolean enabled)
  {
    Logger.i(TAG, "The inertial navigation is turned " + (enabled ? "on" : "off") + " by the user");
    NoGps.nativeSetInertialNavigationEnabled(enabled);
    final NoGps.Status status = NoGps.getStatus();
    if (enabled && status.esp32Source)
      askBoxBluetooth();
    else if (enabled && status.elm327Address.isEmpty())
      withBluetoothPermission(this::chooseAdapter);
    update();
  }

  /**
   * The user picks where the car movement comes from. The box is set up in its own sheet, the adapter is chosen at
   * once if there is none.
   */
  private void onSourceChosen(int radioId)
  {
    final boolean box = radioId == R.id.nogps_source_box;
    if (box == NoGps.getStatus().esp32Source)
      return;
    Logger.i(TAG, "The source is chosen by the user: " + (box ? "the box" : "ELM327 + the gyroscope of the device"));
    if (box)
    {
      NoGps.nativeSetEsp32Source(true);
      if (NoGps.getStatus().inertialEnabled)
        askBoxBluetooth();
      update();
      return;
    }
    NoGps.nativeSetEsp32Source(false);
    if (NoGps.getStatus().inertialEnabled && NoGps.getStatus().elm327Address.isEmpty())
      withBluetoothPermission(this::chooseAdapter);
    update();
  }

  /**
   * The Box sheet connects the box (it is searched step by step there), shows what it tells and updates its firmware.
   */
  private void openBoxSheet()
  {
    if (!NoGps.getStatus().esp32Source)
      NoGps.nativeSetEsp32Source(true);
    if (getParentFragmentManager().findFragmentByTag(BoxBottomSheet.TAG) == null)
      new BoxBottomSheet().show(getParentFragmentManager(), BoxBottomSheet.TAG);
  }

  /**
   * The sensor box is searched and connected over Bluetooth LE: before Android 12 a scan needs the location. Without
   * the permission the state of the box tells so.
   */
  private void askBoxBluetooth()
  {
    if (mBoxBluetoothAsked)
      return;
    final String[] permissions =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ? new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
            : new String[] {Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION};
    for (String permission : permissions)
    {
      if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED)
        continue;
      mBoxBluetoothAsked = true;
      Logger.i(TAG, "Requesting " + String.join(", ", permissions) + " for the box");
      mBoxBluetoothLauncher.launch(permissions);
      return;
    }
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
    final boolean esp32 = status.esp32Source;
    updateSource(status);

    updateGps(status);
    if (!status.inertialEnabled)
      return;

    updateFirmware(started && esp32 ? status : null);

    final int speed = started ? status.speedKmh : -1;
    mSpeed.setText(getString(R.string.nogps_sensors_speed, speed >= 0 ? getString(R.string.nogps_speed_kmh, speed)
                                                                      : getString(R.string.nogps_unknown)));
    updateCar(started && esp32 && status.hasCarInfo ? status : null, speed);

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
    mCalibrate.setText(esp32 ? R.string.nogps_sensors_calibrate_box : R.string.nogps_sensors_calibrate);
    // The box calibrates itself: there is nobody to calibrate while it is out of reach. The phone has nothing to
    // calibrate without a gyroscope.
    mCalibrate.setEnabled(esp32 ? status.getBleState() == NoGps.BleState.CONNECTED : mGyroscope);
    if (esp32)
    {
      mGyro.setText(
          getString(R.string.nogps_sensors_box_sensors, boxCalibrationText(requireContext(), status, calibration)));
    }
    else
    {
      final String gyro = switch (calibration)
      {
        case NONE -> getString(R.string.nogps_gyro_none);
        case CALIBRATING -> getString(R.string.nogps_gyro_calibrating, status.calibrationProgress);
        case DONE -> getString(R.string.nogps_gyro_done);
        case FAILED_MOVING -> getString(R.string.nogps_gyro_failed);
        case MOUNT_MOVED -> getString(R.string.nogps_gyro_mount_moved);
        case LINK_LOST -> getString(R.string.nogps_box_calibration_link_lost);
      };
      mGyro.setText(getString(R.string.nogps_sensors_gyro, gyro));
    }

    if (!started)
    {
      mReadiness.setText("");
      return;
    }
    final List<String> missing = new ArrayList<>();
    if (speed < 0)
      missing.add(getString(R.string.nogps_missing_speed));
    if (calibration != NoGps.CalibrationState.DONE)
      missing.add(getString(esp32 ? R.string.nogps_missing_box_calibration : R.string.nogps_missing_gyro));
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
   * Where the car movement comes from: both choices, and the row of the chosen one under it.
   */
  private void updateSource(@NonNull NoGps.Status status)
  {
    final boolean esp32 = status.esp32Source;
    final int checked = esp32 ? R.id.nogps_source_box : R.id.nogps_source_elm;
    // Also changed by the Box sheet, or by the head unit setup.
    if (mSource.getCheckedRadioButtonId() != checked && !mSourceBox.isPressed() && !mSourceElm.isPressed())
      mSource.check(checked);
    // The adapter can't turn the car without a gyroscope: it is said once, at the top, while it is the source.
    mNoGyro.setVisibility(!mGyroscope && !esp32 ? View.VISIBLE : View.GONE);
    mHint.setText(esp32 ? R.string.nogps_sensors_hint_box : R.string.nogps_sensors_hint);
    mBoxRow.setVisibility(esp32 ? View.VISIBLE : View.GONE);
    mAdapterRow.setVisibility(esp32 ? View.GONE : View.VISIBLE);
    final boolean heard = status.inertialEnabled && BoxBottomSheet.isBoxHeard(status);
    // Under the box choice: the shop is a link, the choices stay the main thing.
    mBuy.setVisibility(esp32 && !heard ? View.VISIBLE : View.GONE);
    if (esp32)
    {
      String box = BoxBottomSheet.boxName(status);
      if (status.inertialStarted)
        box += " · " + boxStateText(requireContext(), status);
      else if (!status.inertialEnabled)
        box += " · " + getString(R.string.nogps_box_not_connected);
      mBox.setText(getString(R.string.nogps_sensors_box, box));
      mBoxOpen.setText(heard ? R.string.nogps_box_settings : R.string.nogps_box_connect_short);
      return;
    }
    String adapter = adapterName(status);
    if (status.inertialStarted)
      adapter += " · " + adapterStateText(requireContext(), status);
    mAdapter.setText(getString(R.string.nogps_sensors_adapter, adapter));
  }

  /**
   * Shows the firmware of the sensor box, the button to send it the one of the application, or how that goes.
   * @param status null if the box is not used.
   */
  private void updateFirmware(@Nullable NoGps.Status status)
  {
    final NoGps.FirmwareUpdate update = status != null ? status.getFirmwareUpdate() : NoGps.FirmwareUpdate.NONE;
    final boolean updating = isUpdating(update);
    if (status == null || (!updating && status.boxFirmware.isEmpty()))
    {
      mFirmwareRow.setVisibility(View.GONE);
      return;
    }
    mFirmwareRow.setVisibility(View.VISIBLE);
    mFirmware.setText(getString(R.string.nogps_sensors_firmware, firmwareText(requireContext(), status)));

    final boolean install = canInstallFirmware(status);
    // A box that has taken the firmware restarts by itself.
    final boolean cancel = updating && update != NoGps.FirmwareUpdate.RESTARTING;
    mFirmwareButton.setVisibility(install || cancel ? View.VISIBLE : View.GONE);
    if (cancel)
      mFirmwareButton.setText(R.string.cancel);
    else if (install)
      mFirmwareButton.setText(getString(R.string.nogps_firmware_install, status.bundledFirmware));
  }

  /**
   * The firmware of the box, or how its update goes. Also shown by the Box sheet.
   */
  @NonNull
  static String firmwareText(@NonNull Context context, @NonNull NoGps.Status status)
  {
    return switch (status.getFirmwareUpdate())
    {
      case STARTING -> context.getString(R.string.nogps_firmware_starting);
      case SENDING -> context.getString(R.string.nogps_firmware_sending, status.firmwareUpdateProgress);
      case VERIFYING -> context.getString(R.string.nogps_firmware_verifying);
      case RESTARTING -> context.getString(R.string.nogps_firmware_restarting);
      case NONE, DONE, FAILED -> status.boxFirmware;
    };
  }

  /**
   * The box works with the firmware the application is tested with: there is nothing to install. A debug build sends
   * it again to try the update itself.
   */
  static boolean canInstallFirmware(@NonNull NoGps.Status status)
  {
    return !isUpdating(status.getFirmwareUpdate()) && !status.bundledFirmware.isEmpty()
 && (BuildConfig.DEBUG || !status.bundledFirmware.equals(status.boxFirmware));
  }

  private static boolean isUpdating(@NonNull NoGps.FirmwareUpdate update)
  {
    return update == NoGps.FirmwareUpdate.STARTING || update == NoGps.FirmwareUpdate.SENDING
 || update == NoGps.FirmwareUpdate.VERIFYING || update == NoGps.FirmwareUpdate.RESTARTING;
  }

  private void onFirmwareButton()
  {
    if (isUpdating(NoGps.getStatus().getFirmwareUpdate()))
      NoGps.nativeCancelFirmwareUpdate();
    else
      NoGps.nativeStartFirmwareUpdate();
    update();
  }

  /**
   * Shows what the sensor box tells about the car: the engine and the voltage measured by the box and by its OBD chip.
   * @param car the status with the car data, null if the box tells nothing.
   */
  private void updateCar(@Nullable NoGps.Status car, int speedKmh)
  {
    mCar.setVisibility(car != null ? View.VISIBLE : View.GONE);
    mVoltageMismatch.setVisibility(car != null && car.voltageMismatch ? View.VISIBLE : View.GONE);
    if (car == null)
      return;

    // The box measures its own voltage all the time and asks the car for the rest only while it stands, not to
    // delay the speed.
    final String unknown = getString(speedKmh > 0 ? R.string.nogps_not_while_driving : R.string.nogps_unknown);
    final String engine = getString(car.engineRunning < 0   ? R.string.nogps_unknown
                                    : car.engineRunning > 0 ? R.string.nogps_engine_running
                                                            : R.string.nogps_engine_stopped);
    final String rpm = car.rpm >= 0 ? getString(R.string.nogps_rpm, car.rpm) : unknown;
    final String boxVolts = car.boxMillivolts >= 0 ? formatVolts(car.boxMillivolts) : getString(R.string.nogps_unknown);
    final String elmVolts = car.elmMillivolts >= 0 ? formatVolts(car.elmMillivolts) : unknown;
    final String ecuVolts = car.ecuMillivolts >= 0 ? formatVolts(car.ecuMillivolts) : unknown;
    mCar.setText(getString(R.string.nogps_sensors_car, engine, rpm, boxVolts, elmVolts, ecuVolts));
  }

  @NonNull
  private String formatVolts(int millivolts)
  {
    return getString(R.string.nogps_volts, String.format(Locale.US, "%.1f", millivolts / 1000.0));
  }

  /**
   * Also shown by the Box sheet.
   */
  @NonNull
  static String boxCalibrationText(@NonNull Context context, @NonNull NoGps.Status status,
                                   @NonNull NoGps.CalibrationState calibration)
  {
    return switch (calibration)
    {
      case NONE -> context.getString(R.string.nogps_box_not_calibrated);
      case CALIBRATING -> context.getString(R.string.nogps_box_calibrating, status.calibrationProgress);
      // The box keeps its calibration, but it may stand in another way since it was plugged in.
      case DONE ->
        context.getString(status.boxCalibrationAdvised ? R.string.nogps_box_calibration_advised
                                                       : R.string.nogps_box_calibrated);
      case FAILED_MOVING -> context.getString(R.string.nogps_box_calibration_failed);
      case MOUNT_MOVED -> context.getString(R.string.nogps_gyro_mount_moved);
      case LINK_LOST -> context.getString(R.string.nogps_box_calibration_link_lost);
    };
  }

  /**
   * What GPS is worth, if the car follows it, and the share of the position with other apps.
   */
  private void updateGps(@NonNull NoGps.Status status)
  {
    final Context context = requireContext();
    mGpsView.update(status);
    // GPS is turned off for the car only while the adapter or the box can drive it: nothing would move the mark
    // otherwise.
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
   * The adapter or the box answers: the car is followed by it, or it tells why not.
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
   * The state of the source of the car movement, the box or the ELM327 adapter. Told by the button of the bottom bar.
   */
  @NonNull
  public static String sourceStateText(@NonNull Context context, @NonNull NoGps.Status status)
  {
    return status.esp32Source ? boxStateText(context, status) : adapterStateText(context, status);
  }

  /**
   * The state of the ELM327 adapter.
   */
  @NonNull
  public static String adapterStateText(@NonNull Context context, @NonNull NoGps.Status status)
  {
    return context.getString(switch (status.getSourceState())
    {
      case DISCONNECTED -> R.string.nogps_elm_disconnected;
      case CONNECTING, OBD_CONNECTING -> R.string.nogps_elm_connecting;
      case NO_ADAPTER, OBD_DISABLED -> R.string.nogps_elm_no_adapter;
      case OBD_ERROR -> R.string.nogps_elm_error;
      // The last ones are of the sensor box only.
      case NO_CAR_DATA, BOX_SLEEPING, OBD_SLEEPING, UPDATING -> R.string.nogps_elm_no_car;
      case CONNECTED -> R.string.nogps_elm_connected;
    });
  }

  /**
   * The state of the box. Also told by the Box sheet.
   */
  @NonNull
  public static String boxStateText(@NonNull Context context, @NonNull NoGps.Status status)
  {
    // The phone keeps looking for a sleeping box.
    if (status.getSourceState() == NoGps.SourceState.BOX_SLEEPING)
      return context.getString(R.string.nogps_box_sleeping) + " · " + context.getString(boxSearchText(context, status));
    return context.getString(switch (status.getSourceState())
    {
      case DISCONNECTED -> R.string.nogps_elm_disconnected;
      case CONNECTING -> boxSearchText(context, status);
      case NO_ADAPTER -> R.string.nogps_elm_no_adapter;
      case OBD_DISABLED -> R.string.nogps_box_obd_disabled;
      case OBD_CONNECTING -> R.string.nogps_box_obd_connecting;
      case OBD_ERROR -> R.string.nogps_elm_error;
      case NO_CAR_DATA -> R.string.nogps_elm_no_car;
      case BOX_SLEEPING -> R.string.nogps_box_sleeping;
      case OBD_SLEEPING -> R.string.nogps_box_obd_sleeping;
      case CONNECTED -> R.string.nogps_elm_connected;
      case UPDATING -> R.string.nogps_box_updating;
    });
  }

  /**
   * The box is not heard yet: what the phone does to reach it.
   */
  private static int boxSearchText(@NonNull Context context, @NonNull NoGps.Status status)
  {
    if (NoGps.isLocationOffForBox(context, status))
      return R.string.nogps_box_step_location_off;
    return switch (status.getBleState())
    {
      // Named as in the settings: Android before 12 finds Bluetooth devices with the location permission.
      case NO_PERMISSION ->
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? R.string.nogps_box_bt_no_nearby
                                                       : R.string.nogps_box_bt_no_location;
      // OFF: the phone has no Bluetooth LE.
      case OFF, DISABLED -> R.string.nogps_box_bt_disabled;
      case SEARCHING -> R.string.nogps_box_bt_searching;
      case PAIRING_CLOSED -> R.string.nogps_box_bt_pairing_closed;
      case CONNECTING -> R.string.nogps_box_bt_connecting;
      case PAIRING -> R.string.nogps_box_bt_pairing;
      case CONNECTED -> R.string.nogps_box_bt_waiting;
    };
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
