#include "testing/testing.hpp"

#include "map/map_tests/nogps_test_env.hpp"

#include "map/nogps/elm327_session.hpp"
#include "map/nogps/phone_motion.hpp"

#include "base/math.hpp"
#include "base/string_utils.hpp"

#include <algorithm>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace nogps_sources_tests
{
using namespace nogps;
using namespace nogps_test;

class SpeedRecorder : public Elm327Session::Listener
{
public:
  void OnSpeed(int speedKmh, int64_t) override { m_speeds.push_back(speedKmh); }

  std::vector<int> m_speeds;
};

class MotionRecorder : public MotionSource::Listener
{
public:
  void OnSpeed(int speedKmh, int64_t) override { m_speeds.push_back(speedKmh); }
  void OnMotion(double yawDeltaDeg, double dtSec, int64_t) override
  {
    m_yawDeg += yawDeltaDeg;
    m_dtSec += dtSec;
    m_maxDtSec = std::max(m_maxDtSec, dtSec);
    ++m_motions;
  }

  std::vector<int> m_speeds;
  double m_yawDeg = 0;
  double m_dtSec = 0;
  double m_maxDtSec = 0;
  int m_motions = 0;
};

// Answers the initialization of the adapter.
void InitElm327(TestClock & clock, TestDelegate & delegate, Elm327Session & session)
{
  session.OnConnected();
  for (char const * command : {"ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"})
  {
    TEST_EQUAL(delegate.m_elm327Writes.back(), std::string(command) + "\r", ());
    clock.Advance(50);
    session.OnBytes("OK\r\r>");
  }
}

UNIT_TEST(NoGps_Elm327Session_InitializesAndPollsSpeed)
{
  TestClock clock;
  TestDelegate delegate;
  SpeedRecorder listener;
  Elm327Session session(delegate, clock, clock, "AA:BB", listener);
  session.Start();
  TEST_EQUAL(delegate.m_elm327Connects, std::vector<std::string>{"AA:BB"}, ());
  TEST_EQUAL(session.GetState(), SourceState::Connecting, ());

  InitElm327(clock, delegate, session);
  TEST_EQUAL(session.GetState(), SourceState::NoCarData, ());
  TEST_EQUAL(delegate.m_elm327Writes.back(), "010D\r", ());

  // The response comes in pieces.
  session.OnBytes("SEARCHING...\r41 0D 3C\r\r");
  TEST(listener.m_speeds.empty(), ());
  session.OnBytes(">");
  TEST_EQUAL(listener.m_speeds, std::vector<int>{60}, ());
  TEST_EQUAL(session.GetState(), SourceState::Connected, ());

  // The next request waits a little not to flood the car bus.
  size_t const writes = delegate.m_elm327Writes.size();
  clock.Advance(Elm327Session::kRequestIntervalMs - 1);
  TEST_EQUAL(delegate.m_elm327Writes.size(), writes, ());
  clock.Advance(1);
  TEST_EQUAL(delegate.m_elm327Writes.size(), writes + 1, ());
  TEST_EQUAL(delegate.m_elm327Writes.back(), "010D\r", ());
}

UNIT_TEST(NoGps_Elm327Session_ReconnectsWithoutCarData)
{
  TestClock clock;
  TestDelegate delegate;
  SpeedRecorder listener;
  Elm327Session session(delegate, clock, clock, "AA:BB", listener);
  session.Start();
  InitElm327(clock, delegate, session);
  session.OnBytes("41 0D 10\r\r>");
  TEST_EQUAL(session.GetState(), SourceState::Connected, ());

  // The ignition is off: the car doesn't answer, the adapter is reset after several requests.
  for (int i = 0; i < Elm327Session::kMaxNoDataRequests; ++i)
  {
    clock.Advance(Elm327Session::kRequestIntervalMs);
    TEST_EQUAL(delegate.m_elm327Writes.back(), "010D\r", ());
    session.OnBytes("NO DATA\r\r>");
    TEST_EQUAL(delegate.m_elm327Closes, i + 1 == Elm327Session::kMaxNoDataRequests ? 1 : 0, ());
    if (i + 1 < Elm327Session::kMaxNoDataRequests)
      clock.Advance(Elm327Session::kReconnectDelayMs - Elm327Session::kRequestIntervalMs);
  }
  TEST_EQUAL(session.GetState(), SourceState::Disconnected, ());
  clock.Advance(Elm327Session::kReconnectDelayMs);
  TEST_EQUAL(delegate.m_elm327Connects.size(), 2, ());
  TEST_EQUAL(session.GetState(), SourceState::Connecting, ());
}

UNIT_TEST(NoGps_Elm327Session_NoAdapterOnTimeout)
{
  TestClock clock;
  TestDelegate delegate;
  SpeedRecorder listener;
  Elm327Session session(delegate, clock, clock, "AA:BB", listener);
  session.Start();
  session.OnConnected();
  // The adapter doesn't answer the reset.
  clock.Advance(Elm327Session::kResponseTimeoutMs);
  TEST_EQUAL(delegate.m_elm327Closes, 1, ());
  TEST_EQUAL(session.GetState(), SourceState::NoAdapter, ());

  // While the adapter doesn't answer, the reconnections don't blink with "connecting".
  clock.Advance(Elm327Session::kReconnectDelayMs);
  TEST_EQUAL(delegate.m_elm327Connects.size(), 2, ());
  TEST_EQUAL(session.GetState(), SourceState::NoAdapter, ());
  session.OnClosed("Connection refused");
  TEST_EQUAL(session.GetState(), SourceState::NoAdapter, ());
  clock.Advance(Elm327Session::kReconnectDelayMs);
  TEST_EQUAL(delegate.m_elm327Connects.size(), 3, ());

  session.Stop();
  TEST_EQUAL(session.GetState(), SourceState::Disconnected, ());
  clock.Advance(10 * Elm327Session::kReconnectDelayMs);
  TEST_EQUAL(delegate.m_elm327Connects.size(), 3, ());
}

UNIT_TEST(NoGps_PhoneMotion_CalibratesAndTurns)
{
  TestClock clock;
  TestDelegate delegate;
  MotionRecorder listener;
  PhoneMotion phone(delegate, clock, clock, {} /* elm327Address */, listener);
  phone.Start();
  TEST_EQUAL(phone.GetState(), SourceState::Disconnected, ());
  // The phone lies flat screen up.
  phone.OnAccel({0, 0, 9.81});
  Vec3 const bias = {0.001, -0.002, 0.003};

  phone.Calibrate();
  for (int i = 0; i <= GyroCalibrator::kRequiredSamples; ++i)
  {
    clock.Advance(20);
    phone.OnGyro(clock.NowMs() * 1'000'000, bias);
  }
  TEST_EQUAL(phone.GetCalibrationState(), CalibrationState::Done, ());
  TEST(phone.IsCalibrated(), ());

  // Turning right at 10 deg/s is a negative rotation around the screen normal.
  double const yawBefore = listener.m_yawDeg;
  for (int i = 0; i < 50; ++i)
  {
    clock.Advance(20);
    phone.OnGyro(clock.NowMs() * 1'000'000, {bias[0], bias[1], bias[2] - math::DegToRad(10.0)});
  }
  TEST_ALMOST_EQUAL_ABS(listener.m_yawDeg - yawBefore, 10.0, 1e-6, ());

  // A break of the sensor is not a rotation.
  clock.Advance(1000);
  int const motions = listener.m_motions;
  phone.OnGyro(clock.NowMs() * 1'000'000, bias);
  TEST_EQUAL(listener.m_motions, motions, ());
}

UNIT_TEST(NoGps_PhoneMotion_CalibrationFailsWhileDriving)
{
  TestClock clock;
  TestDelegate delegate;
  MotionRecorder listener;
  PhoneMotion phone(delegate, clock, clock, std::string("AA:BB"), listener);
  phone.Start();
  TEST_EQUAL(delegate.m_elm327Connects, std::vector<std::string>{"AA:BB"}, ());
  phone.OnAccel({0, 9.81, 0});

  // The car drives straight: the phone is as still as in a standing car, but the speed tells it moves.
  auto * elm327 = phone.GetElm327();
  TEST(elm327, ());
  InitElm327(clock, delegate, *elm327);
  elm327->OnBytes("41 0D 28\r\r>");
  phone.Calibrate();
  for (int i = 0; i <= GyroCalibrator::kRequiredSamples; ++i)
  {
    clock.Advance(20);
    phone.OnGyro(clock.NowMs() * 1'000'000, {0, 0, 0});
  }
  TEST_EQUAL(phone.GetCalibrationState(), CalibrationState::FailedMoving, ());
  TEST(!phone.IsCalibrated(), ());
}
}  // namespace nogps_sources_tests
