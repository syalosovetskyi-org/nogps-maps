#include "testing/testing.hpp"

#include "geometry/mercator.hpp"
#include "traffic/traffic_info.hpp"
#include "traffic_matcher.hpp"

#include <cmath>
#include <numbers>
#include <stdexcept>
#include <string>

namespace traffic_gen_tests
{
using namespace traffic_gen;
using traffic::SpeedGroup;
using Info = traffic::TrafficInfo;

m2::PointD Point(double east, double north)
{
  // Local coordinates in metres around Kyiv.
  return mercator::FromLatLon(50.45 + north / 111195.0,
                              30.52 + east / (111195.0 * std::cos(50.45 * std::numbers::pi / 180)));
}

UNIT_TEST(TrafficMatcher_DirectionDistanceAndNearest)
{
  Matcher const matcher({{{Point(0, 0), Point(100, 0)}, SpeedGroup::G1, Direction::Forward},
                         {{Point(0, 12), Point(100, 12)}, SpeedGroup::TempBlock, Direction::Backward}});
  TEST_EQUAL(matcher.Match(Point(20, 0), Point(80, 0)), SpeedGroup::G1, ());
  TEST_EQUAL(matcher.Match(Point(80, 0), Point(20, 0)), SpeedGroup::TempBlock, ());
  TEST_EQUAL(matcher.Match(Point(20, -14), Point(80, -14)), SpeedGroup::G1, ());
  TEST_EQUAL(matcher.Match(Point(20, -16), Point(80, -16)), SpeedGroup::Unknown, ());
  TEST_EQUAL(matcher.Match(Point(50, -5), Point(50, 5)), SpeedGroup::Unknown, ());
  TEST_EQUAL(matcher.Match(Point(200, 0), Point(220, 0)), SpeedGroup::Unknown, ());
  TEST_EQUAL(matcher.Match(Point(0, 0), Point(0, 0)), SpeedGroup::Unknown, ());
  Matcher const both({{{Point(0, 0), Point(100, 0)}, SpeedGroup::G2, Direction::Both}});
  TEST_EQUAL(both.Match(Point(80, 0), Point(20, 0)), SpeedGroup::G2, ());
  Matcher const nearest({{{Point(0, 0), Point(100, 0)}, SpeedGroup::G1, Direction::Forward},
                         {{Point(0, 5), Point(100, 5)}, SpeedGroup::G3, Direction::Forward}});
  TEST_EQUAL(nearest.Match(Point(20, 4), Point(80, 4)), SpeedGroup::G3, ());
}

UNIT_TEST(TrafficMatcher_Angle)
{
  Matcher const matcher({{{Point(-100, 0), Point(100, 0)}, SpeedGroup::G0, Direction::Forward}});
  auto const endpoint = [](double angle) { return Point(10 * std::cos(angle), 10 * std::sin(angle)); };
  TEST_EQUAL(matcher.Match(Point(0, 0), endpoint(29 * std::numbers::pi / 180)), SpeedGroup::G0, ());
  TEST_EQUAL(matcher.Match(Point(0, 0), endpoint(31 * std::numbers::pi / 180)), SpeedGroup::Unknown, ());
}

std::string Feature(std::string const & properties)
{
  return R"({"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString",
    "coordinates":[[30.52,50.45],[30.53,50.45]]},"properties":)" +
         properties + "}]}";
}

UNIT_TEST(TrafficMatcher_GeoJsonAndWireFormat)
{
  std::vector<SpeedGroup> values;
  for (auto const ratio : {0.0, 0.08, 0.16, 0.33, 0.58, 0.83, 1.0})
    values.push_back(ReadFlow(Feature("{\"speed_ratio\":" + std::to_string(ratio) + "}"))[0].m_speed);
  TEST_EQUAL(values,
             (std::vector<SpeedGroup>{SpeedGroup::G0, SpeedGroup::G0, SpeedGroup::G1, SpeedGroup::G2, SpeedGroup::G3,
                                      SpeedGroup::G4, SpeedGroup::G5}),
             ());
  TEST_EQUAL(ReadFlow(Feature(R"({"jam_level":5})"))[0].m_speed, SpeedGroup::G0, ());
  TEST_EQUAL(ReadFlow(Feature(R"({"closed":true})"))[0].m_speed, SpeedGroup::TempBlock, ());
  TEST(ReadFlow(Feature(R"({"jam_level":0,"direction":"both"})"))[0].m_direction == Direction::Both, ());
  values.push_back(SpeedGroup::TempBlock);
  values.push_back(SpeedGroup::Unknown);
  std::vector<uint8_t> bytes;
  Info::SerializeTrafficValues(values, bytes);
  std::vector<SpeedGroup> decoded;
  Info::DeserializeTrafficValues(bytes, decoded);
  TEST_EQUAL(decoded, values, ());
  std::vector<Info::RoadSegmentId> const keys = {{0, 0, 0}, {0, 0, 1}, {0, 1, 0}, {0, 1, 1}, {8, 0, 0}};
  bytes.clear();
  Info::SerializeTrafficKeys(keys, bytes);
  std::vector<Info::RoadSegmentId> decodedKeys;
  Info::DeserializeTrafficKeys(bytes, decodedKeys);
  TEST_EQUAL(decodedKeys, keys, ());
}

UNIT_TEST(TrafficMatcher_InvalidInput)
{
  for (auto const & properties :
       {"{}", R"({"speed_ratio":1.1})", R"({"jam_level":6})", R"({"speed_ratio":0.2,"direction":"north"})"})
  {
    bool rejected = false;
    try
    {
      ReadFlow(Feature(properties));
    }
    catch (std::exception const &)
    {
      rejected = true;
    }
    TEST(rejected, (properties));
  }
  TEST(ReadFlow(R"({"type":"FeatureCollection","features":[]})").empty(), ());
}
}  // namespace traffic_gen_tests
