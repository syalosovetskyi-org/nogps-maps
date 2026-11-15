#pragma once

#include "map/nogps/motion_source.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <string>

namespace nogps
{
class Clock;

/// The car movement told from outside instead of measured by the phone: a trip driven again from its log, or a test.
/// It is calibrated or not as the recording tells, and the user has nothing to calibrate in it.
class RecordedMotion : public MotionSource
{
public:
  // Without a part for this long the recording is not heard.
  static int64_t constexpr kDataTimeoutMs = 1000;

  struct Part
  {
    // The car speed measured |m_speedAgeMs| before the end of the part, nothing if it is not heard.
    std::optional<int> m_speedKmh;
    int64_t m_speedAgeMs = 0;
    // Clockwise.
    double m_yawDeltaDeg = 0;
    double m_dtSec = 0;
    // The rotation is known: the gyroscope of the recording is zeroed and its place in the car is known.
    bool m_calibrated = true;
    // The acceleration of a sensor fixed in the car along its axes, m/s², if the recording has it.
    std::optional<std::array<double, 3>> m_accel;
  };

  RecordedMotion(Clock const & clock, MotionSource::Listener & listener);

  // MotionSource overrides:
  void Start() override;
  void Stop() override;
  SourceState GetState() const override;
  bool IsCalibrated() const override;
  CalibrationState GetCalibrationState() override;
  int GetCalibrationProgressPercent() const override { return 0; }
  void Calibrate() override;
  std::string GetDeviceName() const override { return {}; }

  /// A part of the movement that ends now.
  void OnPart(Part const & part);

private:
  bool IsHeard() const;

  Clock const & m_clock;
  MotionSource::Listener & m_listener;
  bool m_running = false;
  std::optional<int64_t> m_lastPartMs;
  bool m_calibrated = false;
};
}  // namespace nogps
