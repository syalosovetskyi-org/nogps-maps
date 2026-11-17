package app.organicmaps.sdk.location;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.log.Logger;

/**
 * The accelerometer and the gyroscope of the phone for the navigation without GPS in the core.
 */
class MotionSensors implements SensorEventListener
{
  private static final String TAG = MotionSensors.class.getSimpleName();

  @NonNull
  private final SensorManager mSensorManager;

  MotionSensors(@NonNull Context context)
  {
    mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
  }

  void start(boolean gyroscope)
  {
    stop();
    final Sensor accel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    final boolean accelRegistered =
        accel != null && mSensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
    Logger.i(TAG, "Subscribe accelerometer=" + accelRegistered + " gyroscope requested=" + gyroscope);
    if (!accelRegistered)
      Logger.e(TAG, "Cannot subscribe accelerometer");
    if (!gyroscope)
      return;
    // The core estimates the bias by itself, the calibrated sensor is only a fallback.
    Sensor gyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
    if (gyro == null)
      gyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    if (gyro == null || accel == null)
      Logger.e(TAG, "No gyroscope or accelerometer");
    else
    {
      final boolean registered = mSensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
      Logger.i(TAG, "Subscribe gyroscope=" + registered + " type=" + gyro.getType());
      if (!registered)
        Logger.e(TAG, "Cannot subscribe gyroscope");
    }
  }

  void stop()
  {
    Logger.i(TAG, "Unsubscribe motion sensors");
    mSensorManager.unregisterListener(this);
  }

  @Override
  public void onSensorChanged(SensorEvent event)
  {
    if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
      NoGps.nativeOnAccel(event.timestamp, event.values[0], event.values[1], event.values[2]);
    else
      NoGps.nativeOnGyro(event.timestamp, event.values[0], event.values[1], event.values[2]);
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy)
  {}
}
