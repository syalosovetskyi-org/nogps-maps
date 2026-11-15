#pragma once

#include <array>
#include <cstdint>
#include <string>

namespace nogps
{
enum class SourceState
{
  Disconnected,
  Connecting,
  // The ELM327 adapter doesn't answer: it is not plugged in, switched off or broken.
  NoAdapter,
  // Connected, but the car doesn't tell its speed (ignition off, protocol search).
  NoCarData,
  Connected,
};

enum class CalibrationState
{
  None,
  Calibrating,
  Done,
  // The phone or the car moved during the calibration.
  FailedMoving,
};

std::string DebugPrint(SourceState state);
std::string DebugPrint(CalibrationState state);

/// Where the inertial navigation takes the car movement from: the speed of the car and its rotation.
class MotionSource
{
public:
  class Listener
  {
  public:
    virtual ~Listener() = default;

    /// \param timeMs monotonic time the speed was measured at.
    virtual void OnSpeed(int speedKmh, int64_t timeMs) = 0;

    /// \param yawDeltaDeg the clockwise rotation of the car during |dtSec|, 0 while the source is not calibrated.
    /// \param timestampNs monotonic time of the end of the interval.
    virtual void OnMotion(double yawDeltaDeg, double dtSec, int64_t timestampNs) = 0;

    /// \param accel the acceleration of the source itself along its three fixed axes, m/s², if it has an
    /// accelerometer fixed in the car. No source of the application has one now: the phone moves in its holder.
    virtual void OnSourceAccel(std::array<double, 3> const & /* accel */) {}
  };

  virtual ~MotionSource() = default;

  virtual void Start() = 0;
  virtual void Stop() = 0;

  virtual SourceState GetState() const = 0;

  /// \returns true if the rotation of the car is known: the gyroscope is zeroed and its position in the car is
  /// known.
  virtual bool IsCalibrated() const = 0;
  virtual CalibrationState GetCalibrationState() = 0;
  virtual int GetCalibrationProgressPercent() const = 0;

  /// Zeroes the gyroscope and finds where it looks in the car. The car must stand still for ~3 s.
  virtual void Calibrate() = 0;

  /// \returns the name of the device to show, empty for the phone.
  virtual std::string GetDeviceName() const = 0;
};
}  // namespace nogps
