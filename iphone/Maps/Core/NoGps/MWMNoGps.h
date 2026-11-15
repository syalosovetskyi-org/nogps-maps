#import <CoreLocation/CoreLocation.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

// The orders of the enums are the same as in the core (libs/map/nogps).

typedef NS_ENUM(NSInteger, MWMNoGpsPositionSource) {
  MWMNoGpsPositionSourceNone,
  MWMNoGpsPositionSourceGps,
  // Cell towers and Wi-Fi.
  MWMNoGpsPositionSourceNetwork,
  MWMNoGpsPositionSourceManual,
  // Car speed and turns.
  MWMNoGpsPositionSourceInertial,
} NS_SWIFT_NAME(NoGpsPositionSource);

// What GPS is worth now, whether the car follows it or not.
typedef NS_ENUM(NSInteger, MWMNoGpsGpsState) {
  MWMNoGpsGpsStateNone,
  // Only Wi-Fi and cell towers, or not accurate positions.
  MWMNoGpsGpsStateCoarse,
  MWMNoGpsGpsStateSpoofed,
  // Good positions not trusted yet after GPS was bad, or the satellites are searched after the start.
  MWMNoGpsGpsStateChecking,
  MWMNoGpsGpsStateGood,
} NS_SWIFT_NAME(NoGpsGpsState);

typedef NS_ENUM(NSInteger, MWMNoGpsSourceState) {
  MWMNoGpsSourceStateDisconnected,
  MWMNoGpsSourceStateConnecting,
  MWMNoGpsSourceStateNoAdapter,
  MWMNoGpsSourceStateNoCarData,
  MWMNoGpsSourceStateConnected,
} NS_SWIFT_NAME(NoGpsSourceState);

typedef NS_ENUM(NSInteger, MWMNoGpsCalibrationState) {
  MWMNoGpsCalibrationStateNone,
  MWMNoGpsCalibrationStateCalibrating,
  MWMNoGpsCalibrationStateDone,
  MWMNoGpsCalibrationStateFailedMoving,
} NS_SWIFT_NAME(NoGpsCalibrationState);

typedef NS_ENUM(NSInteger, MWMNoGpsEvent) {
  MWMNoGpsEventManualModeChanged,
  MWMNoGpsEventGpsBack,
  MWMNoGpsEventGpsLost,
  MWMNoGpsEventGpsSpoofed,
  MWMNoGpsEventGpsRestored,
  MWMNoGpsEventRoadLost,
  MWMNoGpsEventTurnsReversed,
  MWMNoGpsEventMotionSourceStopped,
  MWMNoGpsEventMarkNoRoad,
  MWMNoGpsEventNotCalibrated,
} NS_SWIFT_NAME(NoGpsEvent);

/// Posted on the main queue, the MWMNoGpsEvent is in the userInfo by MWMNoGpsEventKey.
extern NSNotificationName const MWMNoGpsEventNotification NS_SWIFT_NAME(NoGps.eventNotification);
extern NSString * const MWMNoGpsEventKey NS_SWIFT_NAME(NoGps.eventKey);

/// Everything the UI shows about the navigation without GPS, taken at once.
NS_SWIFT_NAME(NoGpsStatus)
@interface MWMNoGpsStatus : NSObject

@property(nonatomic, readonly) MWMNoGpsPositionSource source;
// Of the shown position.
@property(nonatomic, readonly) double accuracyM;
@property(nonatomic, readonly) BOOL manualMode;
// The next tap on the map shows where the car is: the button for it is pressed.
@property(nonatomic, readonly) BOOL markByTap;
// Since the user has set the position in the manual mode.
@property(nonatomic, readonly) NSTimeInterval manualAge;
@property(nonatomic, readonly) BOOL gpsSpoofed;
@property(nonatomic, readonly) BOOL gpsDisabled;
@property(nonatomic, readonly) MWMNoGpsGpsState gpsState;
// The age of the last good GPS position, negative if there is none.
@property(nonatomic, readonly) NSTimeInterval lastGoodGpsAge;
// GPS works now, also in the manual mode where it is not used. Negative if it doesn't.
@property(nonatomic, readonly) double workingGpsAccuracyM;
@property(nonatomic, readonly) double workingNetworkAccuracyM;
// The position is not from GPS: the user corrects it with the buttons.
@property(nonatomic, readonly) BOOL movedByHand;
/// The speed limit of the road the car is on, the way it goes: 0 for none, -1 if unknown.
@property(nonatomic, readonly) NSInteger speedLimitKmh;

@property(nonatomic, readonly) BOOL inertialEnabled;
// The rest is known while the inertial navigation works.
@property(nonatomic, readonly) BOOL inertialStarted;
@property(nonatomic, readonly) MWMNoGpsSourceState sourceState;
@property(nonatomic, readonly) NSString * deviceName;
// Negative if unknown.
@property(nonatomic, readonly) NSInteger speedKmh;
@property(nonatomic, readonly) double speedScale;
@property(nonatomic, readonly) NSInteger speedTableRanges;
@property(nonatomic, readonly) NSInteger roughSpeedTableRanges;
/// The ranges of the table in use, the rough one while speedTableRanges is 0: the distance driven by the car speed
/// and the ratio, 0 until the range is measured.
@property(nonatomic, readonly) NSArray<NSNumber *> * speedTableCarM;
@property(nonatomic, readonly) NSArray<NSNumber *> * speedTableScale;
@property(nonatomic, readonly) double speedLagSec;
@property(nonatomic, readonly) BOOL speedLagMeasured;
@property(nonatomic, readonly) MWMNoGpsCalibrationState calibration;
@property(nonatomic, readonly) NSInteger calibrationProgress;
@property(nonatomic, readonly) BOOL hasInertialPosition;
/// The mark waits for the user to put it on a road: the road is lost.
@property(nonatomic, readonly) BOOL roadLost;
/// How many other roads the car may be on after a turn that fitted several, switchMark shows the car on them.
@property(nonatomic, readonly) NSInteger otherMarks;

@end

/// Navigation without GPS, done in the core: it chooses the position to show from GPS and the marks of the user. iOS
/// has no source of the car movement for the inertial navigation: it has no Bluetooth SPP for the ELM327 adapters.
/// Everything is called on the main queue.
NS_SWIFT_NAME(NoGps)
@interface MWMNoGps : NSObject

/// A position from CoreLocation. The chosen position comes back to MWMLocationManager.
+ (void)onLocation:(CLLocation *)location;
/// The last position from CoreLocation as it came: the GPS sheet shows its details.
+ (nullable CLLocation *)lastPlatformLocation;
/// A valid heading of the compass, only written to the log of a drive.
+ (void)onHeading:(CLHeading *)heading;

+ (void)start;
+ (void)stop;

+ (MWMNoGpsStatus *)status;

/// The next single tap on the map shows where the car is, the core handles it. Pass NO to take it back.
+ (void)setMarkByTap:(BOOL)armed;
/// The car is at the point: the mark goes to the nearest road, the map follows the car.
/// @return NO if there is no road near, MWMNoGpsEventMarkNoRoad is posted then.
+ (BOOL)placeMarkAt:(CLLocationCoordinate2D)coordinate;
+ (void)reverseDirection;
/// Shows the car on the next of the other roads it may be on.
+ (void)switchMark;

@end

NS_ASSUME_NONNULL_END
