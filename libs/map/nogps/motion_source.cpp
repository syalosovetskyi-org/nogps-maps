#include "map/nogps/motion_source.hpp"

#include "base/assert.hpp"

namespace nogps
{
std::string DebugPrint(SourceState state)
{
  switch (state)
  {
  case SourceState::Disconnected: return "DISCONNECTED";
  case SourceState::Connecting: return "CONNECTING";
  case SourceState::NoAdapter: return "NO_ADAPTER";
  case SourceState::NoCarData: return "NO_CAR_DATA";
  case SourceState::Connected: return "CONNECTED";
  }
  UNREACHABLE();
}

std::string DebugPrint(CalibrationState state)
{
  switch (state)
  {
  case CalibrationState::None: return "NONE";
  case CalibrationState::Calibrating: return "CALIBRATING";
  case CalibrationState::Done: return "DONE";
  case CalibrationState::FailedMoving: return "FAILED_MOVING";
  }
  UNREACHABLE();
}
}  // namespace nogps
