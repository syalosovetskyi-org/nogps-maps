#import "MWMNoGps.h"
#import "MWMLocationManager.h"
#import "SwiftBridge.h"

#import <CoreApi/AppInfo.h>
#include <CoreApi/Framework.h>

#include "map/nogps/clock.hpp"
#include "map/nogps/delegate.hpp"
#include "map/nogps/engine.hpp"

#include "geometry/mercator.hpp"

#include "base/logging.hpp"

#include <memory>

NSNotificationName const MWMNoGpsEventNotification = @"MWMNoGpsEventNotification";
NSString * const MWMNoGpsEventKey = @"event";

namespace
{
/// The iOS part of the navigation without GPS: GPS and the marks of the user only. The car movement for the inertial
/// navigation is not offered: iOS has no Bluetooth SPP for the ELM327 adapters.
class NoGpsDelegate : public nogps::Delegate
{
public:
  void StartMotionSensors(bool) override {}
  void StopMotionSensors() override {}

  void Elm327Connect(std::string const &) override {}
  void Elm327Write(std::string const &) override {}
  void Elm327Close() override {}

  void OnPosition(nogps::Fix const & fix) override
  {
    CLLocationCoordinate2D const coordinate = CLLocationCoordinate2DMake(fix.m_position.m_lat, fix.m_position.m_lon);
    CLLocation * location =
        [[CLLocation alloc] initWithCoordinate:coordinate
                                      altitude:fix.m_altitudeM.value_or(0)
                            horizontalAccuracy:fix.m_accuracyM
                              verticalAccuracy:fix.m_altitudeM ? fix.m_altitudeAccuracyM.value_or(0) : -1
                                        course:fix.m_bearingDeg.value_or(-1)
                                         speed:fix.m_speedMps.value_or(-1)
                                     timestamp:[NSDate dateWithTimeIntervalSince1970:fix.m_unixTimeMs / 1000.0]];
    bool const fromGps = fix.m_provider == nogps::Provider::Gps || fix.m_provider == nogps::Provider::Fused;
    [MWMLocationManager onNoGpsPosition:location fromGps:fromGps];
  }

  void OnEvent(nogps::Event event) override
  {
    [NSNotificationCenter.defaultCenter postNotificationName:MWMNoGpsEventNotification
                                                      object:nil
                                                    userInfo:@{
                                                      MWMNoGpsEventKey: @(static_cast<NSInteger>(event))
                                                    }];
  }
};

NoGpsDelegate & Delegate()
{
  static NoGpsDelegate delegate;
  return delegate;
}

nogps::Engine & Engine()
{
  static nogps::Engine * engine = nullptr;
  if (!engine)
  {
    engine = &GetFramework().CreateNoGps(Delegate());
    // Nothing drives the car without GPS on iOS: the inertial navigation turned on with the sensor box before it was
    // removed would wait for an ELM327 adapter that iOS can't reach, and GPS ignored for it would leave the mark
    // without a source. Turning it off takes GPS back too.
    if (engine->IsInertialNavigationEnabled())
    {
      LOG(LWARNING, ("The inertial navigation is on without a source of the car movement on iOS, turned off"));
      engine->SetInertialNavigationEnabled(false);
    }
    else if (engine->GetStatus().m_gpsDisabled)
    {
      LOG(LWARNING, ("GPS is ignored without a source of the car movement on iOS, taken back"));
      engine->SetGpsDisabled(false);
    }
  }
  return *engine;
}

std::optional<double> NonNegative(double value)
{
  if (value < 0)
    return {};
  return value;
}
}  // namespace

@interface MWMNoGpsStatus ()

- (instancetype)initWithStatus:(nogps::Status const &)status;

@end

@implementation MWMNoGpsStatus

- (instancetype)initWithStatus:(nogps::Status const &)status
{
  self = [super init];
  if (self)
  {
    _source = static_cast<MWMNoGpsPositionSource>(status.m_source);
    _accuracyM = status.m_accuracyM;
    _manualMode = status.m_manualMode;
    _markByTap = status.m_markByTap;
    _manualAge = status.m_manualAgeMs / 1000.0;
    _gpsSpoofed = status.m_gpsSpoofed;
    _gpsDisabled = status.m_gpsDisabled;
    _gpsState = static_cast<MWMNoGpsGpsState>(status.m_gpsState);
    _lastGoodGpsAge = status.m_lastGoodGpsAgeMs < 0 ? -1 : status.m_lastGoodGpsAgeMs / 1000.0;
    _workingGpsAccuracyM = status.m_workingGpsAccuracyM.value_or(-1);
    _workingNetworkAccuracyM = status.m_workingNetworkAccuracyM.value_or(-1);
    _movedByHand = status.m_movedByHand;
    _speedLimitKmh = status.m_speedLimitKmh;
    _inertialEnabled = status.m_inertialEnabled;
    _inertialStarted = status.m_inertialStarted;
    _sourceState = static_cast<MWMNoGpsSourceState>(status.m_sourceState);
    _deviceName = @(status.m_deviceName.c_str());
    _speedKmh = status.m_speedKmh;
    _speedScale = status.m_speedScale;
    _speedTableRanges = status.m_speedTableRanges;
    _roughSpeedTableRanges = status.m_roughSpeedTableRanges;
    NSMutableArray<NSNumber *> * carM = [NSMutableArray arrayWithCapacity:status.m_speedTable.size()];
    NSMutableArray<NSNumber *> * scale = [NSMutableArray arrayWithCapacity:status.m_speedTable.size()];
    for (auto const & range : status.m_speedTable)
    {
      [carM addObject:@(range.m_carM)];
      [scale addObject:@(range.m_scale)];
    }
    _speedTableCarM = carM;
    _speedTableScale = scale;
    _speedLagSec = status.m_speedLagSec;
    _speedLagMeasured = status.m_speedLagMeasured;
    _calibration = static_cast<MWMNoGpsCalibrationState>(status.m_calibration);
    _calibrationProgress = status.m_calibrationProgress;
    _hasInertialPosition = status.m_hasInertialPosition;
    _roadLost = status.m_roadLost;
    _otherMarks = status.m_otherMarks;
  }
  return self;
}

@end

@implementation MWMNoGps

static CLLocation * lastPlatformLocation;

+ (void)onLocation:(CLLocation *)location
{
  static nogps::SystemClock const clock;
  lastPlatformLocation = location;
  NSTimeInterval const age = -location.timestamp.timeIntervalSinceNow;
  nogps::Fix fix;
  // CoreLocation mixes GPS with Wi-Fi and cell towers, the core tells them apart by the accuracy.
  fix.m_provider = nogps::Provider::Fused;
  fix.m_position = {location.coordinate.latitude, location.coordinate.longitude};
  fix.m_accuracyM = location.horizontalAccuracy;
  fix.m_bearingDeg = NonNegative(location.course);
  fix.m_bearingAccuracyDeg = NonNegative(location.courseAccuracy);
  fix.m_speedMps = NonNegative(location.speed);
  if (location.verticalAccuracy >= 0)
  {
    fix.m_altitudeM = location.altitude;
    fix.m_altitudeAccuracyM = location.verticalAccuracy;
  }
  fix.m_timeMs = clock.NowMs() - static_cast<int64_t>(age * 1000);
  fix.m_unixTimeMs = static_cast<int64_t>(location.timestamp.timeIntervalSince1970 * 1000);
  Engine().OnFix(fix);
}

+ (CLLocation *)lastPlatformLocation
{
  return lastPlatformLocation;
}

+ (void)onHeading:(CLHeading *)heading
{
  Engine().OnCompass(heading.trueHeading >= 0 ? heading.trueHeading : heading.magneticHeading, heading.headingAccuracy);
}

+ (void)start
{
  // A log file of the day goes on over runs, maybe of another build: each run tells its version.
  LOG(LINFO, ("NonGPS Maps", AppInfo.sharedInfo.fullVersion.UTF8String));
  Engine().Start();
}

+ (void)stop
{
  Engine().Stop();
}

+ (MWMNoGpsStatus *)status
{
  return [[MWMNoGpsStatus alloc] initWithStatus:Engine().GetStatus()];
}

+ (void)setMarkByTap:(BOOL)armed
{
  Engine().SetMarkByTap(armed);
}

+ (BOOL)placeMarkAt:(CLLocationCoordinate2D)coordinate
{
  // The framework calls the engine, which is created by the first call to it.
  Engine();
  return GetFramework().PlaceNoGpsMark(mercator::FromLatLon(coordinate.latitude, coordinate.longitude));
}

+ (void)reverseDirection
{
  Engine().ReverseDirection();
}

+ (void)switchMark
{
  Engine().SwitchMark();
}

@end
