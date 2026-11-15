package app.organicmaps.nogps;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import app.organicmaps.sdk.location.NoGps;
import app.organicmaps.sdk.util.log.Logger;
import java.util.Locale;
import java.util.Set;

/**
 * The first start of the head unit build (serg/tasks#2989): the car has a plain ELM327 adapter and no sensor box, so
 * the speed comes from "Phone + ELM327", the inertial navigation is on and the paired adapter is chosen by its name.
 * The defaults are set once; then the choice of the user in the sensors screen is kept.
 */
public final class CarSetup
{
  private static final String TAG = CarSetup.class.getSimpleName();
  private static final String PREFS = "nogps_car_setup";
  private static final String KEY_DEFAULTS_SET = "defaults_set";
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
  }

  private CarSetup() {}

  /**
   * Sets the source and the navigation once and chooses the adapter while none is chosen.
   *
   * @param bluetoothAllowed whether BLUETOOTH_CONNECT is granted (always true before Android 12).
   */
  @UiThread
  @NonNull
  public static Result run(@NonNull Context context, boolean bluetoothAllowed)
  {
    final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    final boolean firstStart = !prefs.getBoolean(KEY_DEFAULTS_SET, false);
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
