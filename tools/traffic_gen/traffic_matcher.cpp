#include "traffic_matcher.hpp"

#include "geometry/mercator.hpp"
#include "geometry/parametrized_segment.hpp"

#include <glaze/json.hpp>

#include <algorithm>
#include <cmath>
#include <optional>
#include <stdexcept>

namespace traffic_gen::json
{
struct Properties
{
  std::optional<double> m_speedRatio;
  std::optional<int> m_jamLevel;
  bool m_closed = false;
  std::string m_direction = "forward";
};
struct Geometry
{
  std::string m_type;
  std::vector<std::vector<double>> m_coordinates;
};
struct Feature
{
  std::string m_type;
  Geometry m_geometry;
  Properties m_properties;
};
struct Collection
{
  std::string m_type;
  std::vector<Feature> m_features;
};
}  // namespace traffic_gen::json

template <>
struct glz::meta<traffic_gen::json::Properties>
{
  using T = traffic_gen::json::Properties;
  static constexpr auto value = glz::object("speed_ratio", &T::m_speedRatio, "jam_level", &T::m_jamLevel, "closed",
                                            &T::m_closed, "direction", &T::m_direction);
};
template <>
struct glz::meta<traffic_gen::json::Geometry>
{
  using T = traffic_gen::json::Geometry;
  static constexpr auto value = glz::object("type", &T::m_type, "coordinates", &T::m_coordinates);
};
template <>
struct glz::meta<traffic_gen::json::Feature>
{
  using T = traffic_gen::json::Feature;
  static constexpr auto value =
      glz::object("type", &T::m_type, "geometry", &T::m_geometry, "properties", &T::m_properties);
};
template <>
struct glz::meta<traffic_gen::json::Collection>
{
  using T = traffic_gen::json::Collection;
  static constexpr auto value = glz::object("type", &T::m_type, "features", &T::m_features);
};

namespace traffic_gen
{
std::vector<FlowLine> ReadFlow(std::string const & geojson)
{
  json::Collection collection;
  if (auto const error = glz::read<glz::opts{.error_on_unknown_keys = false}>(collection, geojson))
    throw std::runtime_error("Invalid traffic GeoJSON: " + glz::format_error(error, geojson));
  if (collection.m_type != "FeatureCollection")
    throw std::runtime_error("Expected a FeatureCollection");

  std::vector<FlowLine> lines;
  for (auto const & feature : collection.m_features)
  {
    if (feature.m_type != "Feature" || feature.m_geometry.m_type != "LineString" ||
        feature.m_geometry.m_coordinates.size() < 2)
      throw std::runtime_error("Expected a Feature with a LineString of at least two points");
    auto const & p = feature.m_properties;
    if (p.m_speedRatio && (!std::isfinite(*p.m_speedRatio) || *p.m_speedRatio < 0 || *p.m_speedRatio > 1))
      throw std::runtime_error("speed_ratio must be in [0, 1]");
    if (p.m_jamLevel && (*p.m_jamLevel < 0 || *p.m_jamLevel > 5))
      throw std::runtime_error("jam_level must be in [0, 5]");
    FlowLine line;
    if (p.m_closed)
      line.m_speed = traffic::SpeedGroup::TempBlock;
    else if (p.m_speedRatio)
      line.m_speed = traffic::GetSpeedGroupByPercentage(*p.m_speedRatio * 100);
    else if (p.m_jamLevel)
      line.m_speed = static_cast<traffic::SpeedGroup>(5 - *p.m_jamLevel);
    else
      throw std::runtime_error("Expected speed_ratio, jam_level or closed=true");

    if (p.m_direction == "forward")
      line.m_direction = Direction::Forward;
    else if (p.m_direction == "backward")
      line.m_direction = Direction::Backward;
    else if (p.m_direction == "both")
      line.m_direction = Direction::Both;
    else
      throw std::runtime_error("direction must be forward, backward or both");

    for (auto const & point : feature.m_geometry.m_coordinates)
    {
      if (point.size() < 2 || !std::isfinite(point[0]) || !std::isfinite(point[1]) || point[0] < -180 ||
          point[0] > 180 || point[1] < -85 || point[1] > 85)
        throw std::runtime_error("Invalid WGS84 coordinate");
      line.m_points.push_back(mercator::FromLatLon(point[1], point[0]));
    }
    lines.push_back(std::move(line));
  }
  return lines;
}

Matcher::Matcher(std::vector<FlowLine> const & lines)
{
  for (auto const & line : lines)
    for (size_t i = 1; i < line.m_points.size(); ++i)
    {
      auto const & from = line.m_points[i - 1];
      auto const & to = line.m_points[i];
      if (from == to)
        continue;
      m2::RectD rect(from, to);
      // Tree rectangles must have nonzero area, including axis-aligned lines.
      rect.Inflate(1e-10, 1e-10);
      m_index.Add(m_parts.size(), rect);
      m_parts.push_back({from, to, line.m_speed, line.m_direction});
    }
}

traffic::SpeedGroup Matcher::Match(m2::PointD const & from, m2::PointD const & to) const
{
  auto const road = to - from;
  if (road.SquaredLength() == 0)
    return traffic::SpeedGroup::Unknown;
  auto const center = (from + to) * 0.5;
  double bestDistance = 15.0;
  size_t bestIndex = m_parts.size();
  auto result = traffic::SpeedGroup::Unknown;
  m_index.ForEachInRect(mercator::RectByCenterXYAndSizeInMeters(center, 30.0), [&](size_t index)
  {
    auto const & part = m_parts[index];
    auto source = part.m_to - part.m_from;
    if (part.m_direction == Direction::Backward)
      source = source * -1.0;
    double dot = m2::DotProduct(road, source);
    if (part.m_direction == Direction::Both)
      dot = std::abs(dot);
    // Mercator preserves local angles. cos(30 degrees) = sqrt(3) / 2.
    if (dot < std::sqrt(road.SquaredLength() * source.SquaredLength()) * std::sqrt(3.0) / 2)
      return;
    auto const closest = m2::ParametrizedSegment<m2::PointD>(part.m_from, part.m_to).ClosestPointTo(center);
    double const distance = mercator::DistanceOnEarth(center, closest);
    if (distance < bestDistance || (distance == bestDistance && index < bestIndex))
    {
      bestDistance = distance;
      bestIndex = index;
      result = part.m_speed;
    }
  });
  return result;
}
}  // namespace traffic_gen
