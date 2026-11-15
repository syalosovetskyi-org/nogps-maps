#include "map/nogps/recorded_motion.hpp"

#include "map/nogps/clock.hpp"

#include "base/logging.hpp"

namespace nogps
{
RecordedMotion::RecordedMotion(Clock const & clock, MotionSource::Listener & listener)
  : m_clock(clock)
  , m_listener(listener)
{}

void RecordedMotion::Start()
{
  m_running = true;
}

void RecordedMotion::Stop()
{
  m_running = false;
  m_lastPartMs.reset();
}

bool RecordedMotion::IsHeard() const
{
  return m_running && m_lastPartMs && m_clock.NowMs() - *m_lastPartMs <= kDataTimeoutMs;
}

SourceState RecordedMotion::GetState() const
{
  if (!m_running)
    return SourceState::Disconnected;
  return IsHeard() ? SourceState::Connected : SourceState::Connecting;
}

bool RecordedMotion::IsCalibrated() const
{
  return IsHeard() && m_calibrated;
}

CalibrationState RecordedMotion::GetCalibrationState()
{
  return IsCalibrated() ? CalibrationState::Done : CalibrationState::None;
}

void RecordedMotion::Calibrate()
{
  // The recording is calibrated or not as it was recorded: the request is only seen in the log.
  LOG(LWARNING, ("A recorded movement can't be calibrated, it is calibrated =", m_calibrated));
}

void RecordedMotion::OnPart(Part const & part)
{
  if (!m_running)
    return;
  int64_t const now = m_clock.NowMs();
  bool const fresh = IsHeard();
  m_lastPartMs = now;
  m_calibrated = part.m_calibrated;
  if (part.m_speedKmh)
    m_listener.OnSpeed(*part.m_speedKmh, now - part.m_speedAgeMs);
  if (part.m_accel)
    m_listener.OnSourceAccel(*part.m_accel);
  // The rotation of a part after a pause is not counted: the car could do anything meanwhile.
  if (fresh)
    m_listener.OnMotion(part.m_calibrated ? part.m_yawDeltaDeg : 0, part.m_dtSec, now * 1'000'000);
}
}  // namespace nogps
