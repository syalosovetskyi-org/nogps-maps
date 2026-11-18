#pragma once

#include "traffic/speed_groups.hpp"

#include "geometry/point2d.hpp"
#include "geometry/tree4d.hpp"

#include <string>
#include <vector>

namespace traffic_gen
{
// Coordinates are Mercator; direction is relative to the input coordinate order.
enum class Direction
{
  Forward,
  Backward,
  Both
};

struct FlowLine
{
  std::vector<m2::PointD> m_points;
  traffic::SpeedGroup m_speed = traffic::SpeedGroup::Unknown;
  Direction m_direction = Direction::Forward;
};

std::vector<FlowLine> ReadFlow(std::string const & geojson);

class Matcher
{
public:
  explicit Matcher(std::vector<FlowLine> const & lines);
  traffic::SpeedGroup Match(m2::PointD const & from, m2::PointD const & to) const;

private:
  struct Part
  {
    m2::PointD m_from;
    m2::PointD m_to;
    traffic::SpeedGroup m_speed;
    Direction m_direction;
  };
  std::vector<Part> m_parts;
  m4::Tree<size_t> m_index;
};
}  // namespace traffic_gen
