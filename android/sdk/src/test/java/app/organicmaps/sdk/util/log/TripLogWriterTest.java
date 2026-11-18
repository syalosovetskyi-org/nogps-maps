package app.organicmaps.sdk.util.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TripLogWriterTest
{
  @Rule
  public TemporaryFolder mFolder = new TemporaryFolder();

  @Test
  public void tripLine_removesAdapterAddressesFromExceptionsButKeepsCoordinates()
  {
    assertEquals("Failed <adapter> pos=50.450000,30.520000",
                 Logger.sanitizeTripLine("Failed aa:BB:12:34:56:78 pos=50.450000,30.520000"));
  }

  @Test
  public void tripSelection_includesNativeCoreAndLifecycleWithoutGeneralAppLogs()
  {
    assertTrue(Logger.isTripLog(null, "nogps/engine.cpp:1400 LogTrip(): TRIP src=INERTIAL"));
    assertTrue(Logger.isTripLog("MockLocationShare", "SHARING -> PROBING"));
    assertTrue(Logger.isTripLog("NoGpsLifecycle", "Activity onPause"));
    assertTrue(Logger.isTripLog("NavigationService", "Starting Navigation Foreground service"));
    assertFalse(Logger.isTripLog(null, "editor/editor.cpp:100 User data"));
    assertFalse(Logger.isTripLog("MwmActivity", "Other activity data"));
  }

  @Test
  public void tripWriter_appendsAcrossRestartsAndRotatesWithoutDeviceInformation() throws IOException
  {
    final File folder = mFolder.getRoot();
    final Logger.LogFileWriter first = new Logger.LogFileWriter(true);
    first.writeSync(folder.getPath(), "TRIP src=INERTIAL");
    first.close();
    final Logger.LogFileWriter second = new Logger.LogFileWriter(true);
    second.writeSync(folder.getPath(), "PROBING");
    final String appended = Files.readString(folder.listFiles()[0].toPath(), StandardCharsets.UTF_8);
    assertTrue(appended.startsWith("Datetime: "));
    assertTrue(appended.contains("TRIP src=INERTIAL"));
    assertTrue(appended.contains("PROBING"));
    // UTF-8 size, rather than Java character count, determines rotation.
    final String data = "ё".repeat(250_000);
    for (int i = 0; i < 45; ++i)
      second.writeSync(folder.getPath(), data);
    second.close();

    long total = 0;
    for (File file : folder.listFiles())
    {
      assertTrue(file.getName(), file.getName().matches("trip-\\d{4}-\\d{2}-\\d{2}(\\.\\d+)?\\.log"));
      assertTrue(file.length() <= 3_000_000);
      total += file.length();
      final String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
      assertTrue(content.contains(data));
    }
    assertTrue(total <= LogFiles.MAX_TRIP_SIZE);
    assertTrue(folder.listFiles().length > 1);
  }
}
