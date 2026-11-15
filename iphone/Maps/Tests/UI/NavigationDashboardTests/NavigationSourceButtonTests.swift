@testable import Organic_Maps__Debug_
import XCTest

final class NavigationSourceButtonTests: XCTestCase {
  /// iOS has no source of the car movement: the navigation button shows GPS, also for a mark set by hand.
  func test_GivenGpsState_WhenBarLookIsTaken_ThenItIsTheLookOfGps() {
    let cases: [(state: NoGpsGpsState, disabled: Bool, color: UIColor, badge: String?, pulsing: Bool)] = [
      (.good, false, NoGpsStatusBar.goodColor, nil, false),
      (.checking, false, .systemBlue, nil, true),
      (.coarse, false, .systemOrange, nil, false),
      (.spoofed, false, .systemOrange, "exclamationmark", false),
      (.none, false, .systemRed, nil, false),
      (.good, true, NoGpsStatusBar.goodColor, "pause.fill", false),
    ]
    for source in [NoGpsPositionSource.gps, .manual] {
      for c in cases {
        let status = FakeStatus(source: source, gpsState: c.state)
        status.fakeGpsDisabled = c.disabled
        let look = NoGpsStatusBar.barLook(status)
        XCTAssertEqual(look.color, c.color, "source \(source.rawValue), GPS \(c.state.rawValue)")
        XCTAssertEqual(look.badge?.symbol, c.badge, "source \(source.rawValue), GPS \(c.state.rawValue)")
        XCTAssertEqual(look.pulsing, c.pulsing, "source \(source.rawValue), GPS \(c.state.rawValue)")
      }
    }
  }
}

/// A status with the values a test sets.
private final class FakeStatus: NoGpsStatus {
  let fakeSource: NoGpsPositionSource
  let fakeGpsState: NoGpsGpsState
  var fakeGpsDisabled = false

  init(source: NoGpsPositionSource, gpsState: NoGpsGpsState) {
    fakeSource = source
    fakeGpsState = gpsState
    super.init()
  }

  override var source: NoGpsPositionSource { fakeSource }
  override var gpsState: NoGpsGpsState { fakeGpsState }
  override var gpsDisabled: Bool { fakeGpsDisabled }
}
