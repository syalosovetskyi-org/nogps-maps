package app.organicmaps.maplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.organicmaps.R;
import app.organicmaps.sdk.location.NoGps;
import app.organicmaps.sdk.location.NoGps.BleState;
import app.organicmaps.sdk.location.NoGps.GpsState;
import app.organicmaps.sdk.location.NoGps.PositionSource;
import app.organicmaps.sdk.location.NoGps.SourceState;
import java.lang.reflect.Field;
import org.junit.Test;

public class StatusBarTest
{
  // The source button of the navigation menu shows the inertial navigation when the car follows it, or when the mark
  // is set by hand with the inertial navigation on. Else it shows GPS.
  @Test
  public void navButtonShowsInertialOnlyForInertial()
  {
    assertFalse(StatusBar.navShowsInertial(PositionSource.GPS, true));
    assertFalse(StatusBar.navShowsInertial(PositionSource.NETWORK, true));
    assertTrue(StatusBar.navShowsInertial(PositionSource.INERTIAL, true));
    assertTrue(StatusBar.navShowsInertial(PositionSource.MANUAL, true));
    assertFalse(StatusBar.navShowsInertial(PositionSource.MANUAL, false));
    assertFalse(StatusBar.navShowsInertial(PositionSource.NONE, true));
    assertFalse(StatusBar.navShowsInertial(PositionSource.NONE, false));
  }

  // GPS not used by the car keeps the color of its signal and gets the pause badge.
  @Test
  public void disabledGpsShowsItsSignal()
  {
    final NoGps.Status good = gpsStatus(GpsState.GOOD);
    good.gpsDisabled = true;
    final StatusBar.Look look = StatusBar.getGpsLook(good);
    assertEquals(R.color.source_good, look.color);
    assertEquals(R.drawable.ic_nogps_badge_pause, look.badge);

    final NoGps.Status none = gpsStatus(GpsState.NONE);
    none.gpsDisabled = true;
    assertEquals(R.color.source_bad, StatusBar.getGpsLook(none).color);
  }

  // The spoofing badge matters more than the pause one.
  @Test
  public void disabledSpoofedGpsKeepsTheAlert()
  {
    final NoGps.Status status = gpsStatus(GpsState.SPOOFED);
    status.gpsDisabled = true;
    assertEquals(R.drawable.ic_nogps_badge_alert, StatusBar.getGpsLook(status).badge);
  }

  @Test
  public void usedGpsHasNoBadge()
  {
    assertEquals(0, StatusBar.getGpsLook(gpsStatus(GpsState.GOOD)).badge);
  }

  // The adapter is searched over Bluetooth: the button pulses with the Bluetooth icon.
  @Test
  public void connectingAdapterShowsBluetooth()
  {
    final StatusBar.Look look = StatusBar.getInertialLook(inertialStatus(SourceState.CONNECTING));
    assertEquals(R.drawable.ic_nogps_bluetooth, look.icon);
    assertTrue(look.pulsing);
    assertEquals(0, look.badge);
  }

  // The adapter answers, but the car doesn't: an alert.
  @Test
  public void adapterWithoutCarShowsAlert()
  {
    final StatusBar.Look look = StatusBar.getInertialLook(inertialStatus(SourceState.NO_CAR_DATA));
    assertEquals(R.drawable.ic_nogps_inertial, look.icon);
    assertEquals(R.color.source_warning, look.color);
    assertEquals(R.drawable.ic_nogps_badge_alert, look.badge);
  }

  // Off: the button calls to turn it on.
  @Test
  public void inertialOffShowsPlus()
  {
    final StatusBar.Look look = StatusBar.getInertialLook(new NoGps.Status());
    assertEquals(R.drawable.ic_nogps_inertial, look.icon);
    assertEquals(R.drawable.ic_nogps_badge_add, look.badge);
  }

  // The phone looks for a sleeping box: the button shows the search with the moon.
  @Test
  public void sleepingBoxShowsTheSearch()
  {
    final StatusBar.Look look = StatusBar.getInertialLook(boxStatus(SourceState.BOX_SLEEPING, BleState.SEARCHING));
    assertEquals(R.drawable.ic_nogps_bluetooth, look.icon);
    assertTrue(look.pulsing);
    assertEquals(R.drawable.ic_nogps_badge_sleep, look.badge);
  }

  // Bluetooth off matters more than the sleep: the box would not be found when it wakes.
  @Test
  public void sleepingBoxWithBluetoothOffShowsIt()
  {
    final StatusBar.Look look = StatusBar.getInertialLook(boxStatus(SourceState.BOX_SLEEPING, BleState.DISABLED));
    assertEquals(R.drawable.ic_nogps_bluetooth_off, look.icon);
    assertFalse(look.pulsing);
    assertEquals(0, look.badge);
  }

  // The OBD of a box that is heard sleeps: nothing is searched.
  @Test
  public void sleepingObdShowsTheMoon()
  {
    final StatusBar.Look look = StatusBar.getInertialLook(boxStatus(SourceState.OBD_SLEEPING, BleState.CONNECTED));
    assertEquals(R.drawable.ic_nogps_box, look.icon);
    assertFalse(look.pulsing);
    assertEquals(R.drawable.ic_nogps_badge_sleep, look.badge);
  }

  // The box moves the mark: its own icon, purple.
  @Test
  public void connectedBoxShowsTheBox()
  {
    final StatusBar.Look look = StatusBar.getInertialLook(boxStatus(SourceState.CONNECTED, BleState.CONNECTED));
    assertEquals(R.drawable.ic_nogps_box, look.icon);
    assertEquals(R.color.source_inertial, look.color);
  }

  // GPS moves the mark: its look, no badge when the adapter is not a problem.
  @Test
  public void barShowsGpsGood()
  {
    final StatusBar.Look look = StatusBar.getBarLook(inertialSetUp(gpsStatus(GpsState.GOOD, PositionSource.GPS)));
    assertEquals(R.color.source_good, look.color);
    assertEquals(R.drawable.ic_nogps_gps, look.icon);
    assertFalse(look.pulsing);
    assertEquals(0, look.badge);
  }

  @Test
  public void barShowsGpsChecking()
  {
    final StatusBar.Look look = StatusBar.getBarLook(inertialSetUp(gpsStatus(GpsState.CHECKING, PositionSource.GPS)));
    assertEquals(R.color.source_connecting, look.color);
    assertTrue(look.pulsing);
  }

  @Test
  public void barShowsGpsCoarse()
  {
    final StatusBar.Look look = StatusBar.getBarLook(inertialSetUp(gpsStatus(GpsState.COARSE, PositionSource.NETWORK)));
    assertEquals(R.color.source_warning, look.color);
    assertEquals(0, look.badge);
  }

  @Test
  public void barShowsGpsSpoofed()
  {
    final StatusBar.Look look = StatusBar.getBarLook(inertialSetUp(gpsStatus(GpsState.SPOOFED, PositionSource.GPS)));
    assertEquals(R.color.source_warning, look.color);
    assertEquals(R.drawable.ic_nogps_badge_alert, look.badge);
  }

  @Test
  public void barShowsNoGps()
  {
    final StatusBar.Look look = StatusBar.getBarLook(inertialSetUp(gpsStatus(GpsState.NONE, PositionSource.NONE)));
    assertEquals(R.color.source_bad, look.color);
    assertEquals(R.drawable.ic_nogps_gps_off, look.icon);
  }

  // GPS not used by the car keeps the color of its signal and gets the pause badge.
  @Test
  public void barShowsDisabledGps()
  {
    final NoGps.Status status = inertialSetUp(gpsStatus(GpsState.GOOD, PositionSource.MANUAL));
    status.inertialEnabled = false;
    status.gpsDisabled = true;
    final StatusBar.Look look = StatusBar.getBarLook(status);
    assertEquals(R.color.source_good, look.color);
    assertEquals(R.drawable.ic_nogps_badge_pause, look.badge);
  }

  // The inertial navigation moves the mark: purple.
  @Test
  public void barShowsInertial()
  {
    final NoGps.Status status = inertialStatus(SourceState.CONNECTED);
    setState(status, "source", PositionSource.INERTIAL.ordinal());
    setState(status, "gpsState", GpsState.GOOD.ordinal());
    final StatusBar.Look look = StatusBar.getBarLook(status);
    assertEquals(R.drawable.ic_nogps_inertial, look.icon);
    assertEquals(R.color.source_inertial, look.color);
    assertEquals(0, look.badge);
  }

  // The mark is set by hand with the inertial navigation on: its look.
  @Test
  public void barShowsInertialForManual()
  {
    final NoGps.Status status = inertialStatus(SourceState.CONNECTED);
    setState(status, "source", PositionSource.MANUAL.ordinal());
    setState(status, "gpsState", GpsState.GOOD.ordinal());
    assertEquals(R.drawable.ic_nogps_inertial, StatusBar.getBarLook(status).icon);
  }

  // GPS moves the mark: an adapter that is searched is not shown, and a blue one gives no dot.
  @Test
  public void barIgnoresConnectingAdapterWhileGpsIsGood()
  {
    final NoGps.Status status = inertialStatus(SourceState.CONNECTING);
    setState(status, "source", PositionSource.GPS.ordinal());
    setState(status, "gpsState", GpsState.GOOD.ordinal());
    final StatusBar.Look look = StatusBar.getBarLook(status);
    assertEquals(R.color.source_good, look.color);
    assertEquals(R.drawable.ic_nogps_gps, look.icon);
    assertEquals(0, look.badge);
  }

  // With the inertial navigation off the button invites to turn it on.
  @Test
  public void barShowsPlusWithoutInertial()
  {
    final StatusBar.Look look = StatusBar.getBarLook(gpsStatus(GpsState.GOOD, PositionSource.GPS));
    assertEquals(R.drawable.ic_nogps_badge_add, look.badge);
    assertEquals(R.color.source_good, look.color);
  }

  // The look's own badge goes before the plus.
  @Test
  public void barKeepsOwnBadgeWithoutInertial()
  {
    final StatusBar.Look look = StatusBar.getBarLook(gpsStatus(GpsState.SPOOFED, PositionSource.GPS));
    assertEquals(R.drawable.ic_nogps_badge_alert, look.badge);
  }

  // The inertial navigation moves the mark while GPS is lost: a red dot of GPS.
  @Test
  public void barShowsDotOfOtherSourceWhenBad()
  {
    final NoGps.Status status = inertialStatus(SourceState.CONNECTED);
    setState(status, "source", PositionSource.INERTIAL.ordinal());
    setState(status, "gpsState", GpsState.NONE.ordinal());
    final StatusBar.Look look = StatusBar.getBarLook(status);
    assertEquals(R.drawable.ic_nogps_badge_dot, look.badge);
    assertEquals(R.color.source_bad, look.badgeColor);

    setState(status, "gpsState", GpsState.COARSE.ordinal());
    assertEquals(R.color.source_warning, StatusBar.getBarLook(status).badgeColor);

    setState(status, "gpsState", GpsState.CHECKING.ordinal());
    assertEquals(0, StatusBar.getBarLook(status).badge);
  }

  // GPS moves the mark while the adapter has a car-data problem: an orange dot of the inertial navigation.
  @Test
  public void barShowsDotOfAdapterProblem()
  {
    final NoGps.Status status = inertialStatus(SourceState.NO_CAR_DATA);
    setState(status, "source", PositionSource.GPS.ordinal());
    setState(status, "gpsState", GpsState.GOOD.ordinal());
    final StatusBar.Look look = StatusBar.getBarLook(status);
    assertEquals(R.drawable.ic_nogps_badge_dot, look.badge);
    assertEquals(R.color.source_warning, look.badgeColor);
  }

  private static NoGps.Status gpsStatus(GpsState gps, PositionSource source)
  {
    final NoGps.Status status = gpsStatus(gps);
    setState(status, "source", source.ordinal());
    return status;
  }

  // An adapter that is connected and does not draw attention.
  private static NoGps.Status inertialSetUp(NoGps.Status status)
  {
    status.inertialEnabled = true;
    setState(status, "sourceState", SourceState.CONNECTED.ordinal());
    return status;
  }

  private static NoGps.Status gpsStatus(GpsState gps)
  {
    final NoGps.Status status = new NoGps.Status();
    setState(status, "gpsState", gps.ordinal());
    return status;
  }

  private static NoGps.Status inertialStatus(SourceState source)
  {
    final NoGps.Status status = new NoGps.Status();
    status.inertialEnabled = true;
    setState(status, "sourceState", source.ordinal());
    return status;
  }

  private static NoGps.Status boxStatus(SourceState source, BleState ble)
  {
    final NoGps.Status status = inertialStatus(source);
    status.esp32Source = true;
    setState(status, "bleState", ble.ordinal());
    return status;
  }

  // The states are set by JNI only.
  private static void setState(NoGps.Status status, String name, int value)
  {
    try
    {
      final Field field = NoGps.Status.class.getDeclaredField(name);
      field.setAccessible(true);
      field.setInt(status, value);
    }
    catch (ReflectiveOperationException e)
    {
      throw new AssertionError(e);
    }
  }
}
