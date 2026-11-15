package app.organicmaps.nogps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import app.organicmaps.sdk.location.NoGps;
import app.organicmaps.sdk.util.log.Logger;
import java.util.Locale;
import java.util.Set;

/**
 * The first start of the head unit build (serg/tasks#2989, #3031). A head unit without a gyroscope can follow the car
 * only by the NonGPS box (ESP32 with its own gyroscope and ELM327, over Bluetooth LE): the box is the source. One with
 * a gyroscope has a plain ELM327 adapter: the speed comes from "Phone + ELM327" and the paired adapter is chosen by
 * its name. The inertial navigation is on in both. The defaults are set once; then the choice of the user in the
 * sensors screen is kept.
 */
public final class CarSetup
{
  private static final String TAG = CarSetup.class.getSimpleName();
  private static final String PREFS = "nogps_car_setup";
  private static final String KEY_DEFAULTS_SET = "defaults_set";
  // The source is chosen by the gyroscope once, also where the defaults were set before the box came back.
  private static final String KEY_SOURCE_SET = "source_set";
  // The names cheap adapters show, the best first: Vgate iCar Pro is "V-LINK".
  private static final String[] ADAPTER_NAMES = {"v-link", "vgate", "icar", "obd", "elm"};

  public enum Result
  {
    // The adapter was chosen before: nothing to do.
    ALREADY_CHOSEN,
    CHOSEN,
    // Bluetooth is off or missing, the adapter will be looked for on the next start.
    NO_BLUETOOTH,
    // No paired device looks like an OBD adapter: it must be paired or chosen by hand.
    NO_ADAPTER,
    NO_PERMISSION,
    // The head unit has no gyroscope: the box is the source, chosen now. It is to be paired in its sheet.
    BOX_CHOSEN,
    // The box is the source, chosen before.
    BOX,
  }

  private CarSetup() {}

  /**
   * @return true if the head unit has a gyroscope: "Phone + ELM327" can turn the car, otherwise only the box can.
   */
  public static boolean hasGyroscope(@NonNull Context context)
  {
    // As the sensors sheet tells it: TYPE_GYROSCOPE, or the uncalibrated one MotionSensors takes first.
    return SensorsBottomSheet.hasGyroscope(context);
  }

  /**
   * @return true if the car is followed by the box: the source the user has chosen since the setup, or the one the
   * setup will choose by the gyroscope.
   */
  private static boolean usesBox(@NonNull Context context)
  {
    if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SOURCE_SET, false))
      return NoGps.getStatus().esp32Source;
    return !hasGyroscope(context);
  }

  /**
   * The permissions the source of this head unit needs, to be asked one by one: the box is searched over Bluetooth
   * LE (a scan needs the location before Android 12), the adapter is reached over Bluetooth.
   */
  @NonNull
  public static String[] getPermissions(@NonNull Context context)
  {
    if (usesBox(context))
    {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        return new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
      return new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      return new String[] {Manifest.permission.BLUETOOTH_CONNECT};
    return new String[0];
  }

  /**
   * Sets the source and the navigation once and chooses the adapter while none is chosen.
   *
   * @param bluetoothAllowed whether the permissions of getPermissions() are granted.
   */
  @UiThread
  @NonNull
  public static Result run(@NonNull Context context, boolean bluetoothAllowed)
  {
    final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    final boolean firstStart = !prefs.getBoolean(KEY_DEFAULTS_SET, false);
    final boolean sourceSet = prefs.getBoolean(KEY_SOURCE_SET, false);
    final boolean gyroscope = hasGyroscope(context);
    if (!sourceSet)
    {
      // Before the inertial navigation is turned on: it starts with this source at once.
      NoGps.nativeSetEsp32Source(!gyroscope);
      prefs.edit().putBoolean(KEY_SOURCE_SET, true).apply();
      Logger.i(TAG, "Head unit source: " + (gyroscope ? "Phone + ELM327" : "the box, there is no gyroscope"));
    }
    if (NoGps.getStatus().esp32Source)
    {
      if (firstStart)
        setDefaults(prefs);
      final Result result = !bluetoothAllowed ? Result.NO_PERMISSION : sourceSet ? Result.BOX : Result.BOX_CHOSEN;
      Logger.i(TAG, "Head unit setup: " + result + (firstStart ? ", defaults set" : ""));
      return result;
    }
    if (!NoGps.getStatus().elm327Address.isEmpty())
    {
      if (firstStart)
        setDefaults(prefs);
      return Result.ALREADY_CHOSEN;
    }

    Result result = Result.NO_PERMISSION;
    if (bluetoothAllowed)
    {
      final String address = findAdapter();
      if (address == null)
        result = BluetoothAdapter.getDefaultAdapter() == null || !BluetoothAdapter.getDefaultAdapter().isEnabled()
                   ? Result.NO_BLUETOOTH
                   : Result.NO_ADAPTER;
      else
      {
        // Before the source is switched: the phone source starts with this adapter at once.
        NoGps.nativeSetElm327Address(address);
        result = Result.CHOSEN;
      }
    }
    if (firstStart)
      setDefaults(prefs);
    Logger.i(TAG, "Head unit setup: " + result + (firstStart ? ", defaults set" : ""));
    return result;
  }

  private static void setDefaults(@NonNull SharedPreferences prefs)
  {
    NoGps.nativeSetInertialNavigationEnabled(true);
    prefs.edit().putBoolean(KEY_DEFAULTS_SET, true).apply();
  }

  /**
   * The address of the paired device whose name looks most like an OBD adapter, null if there is none.
   */
  @SuppressLint("MissingPermission") // The caller checks BLUETOOTH_CONNECT.
  @Nullable
  private static String findAdapter()
  {
    final BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
    if (bluetooth == null || !bluetooth.isEnabled())
      return null;
    final Set<BluetoothDevice> bonded;
    try
    {
      bonded = bluetooth.getBondedDevices();
    }
    catch (SecurityException e)
    {
      Logger.w(TAG, "Paired devices are not readable: " + e.getMessage());
      return null;
    }
    if (bonded == null)
      return null;
    String best = null;
    int bestRank = ADAPTER_NAMES.length;
    for (BluetoothDevice device : bonded)
    {
      final String name = device.getName();
      if (name == null)
        continue;
      final String lower = name.toLowerCase(Locale.ROOT);
      for (int rank = 0; rank < bestRank; rank++)
      {
        if (lower.contains(ADAPTER_NAMES[rank]))
        {
          best = device.getAddress();
          bestRank = rank;
          break;
        }
      }
    }
    return best;
  }
}
