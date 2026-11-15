#include "map/nogps/service.hpp"

#include "map/routing_manager.hpp"

#include "routing/following_info.hpp"

#include "indexer/data_source.hpp"

#include "platform/location.hpp"
#include "platform/platform.hpp"

#include "base/logging.hpp"

namespace nogps
{
namespace
{
// The core takes a negative bearing for an unknown one: it is built with fast math, which does not support NaN.
double ToCoreBearing(std::optional<double> bearingDeg)
{
  return bearingDeg.value_or(-1.0);
}
}  // namespace

std::optional<RoadPoint> RoutingMapApi::RoutingRoads::Snap(ms::LatLon const & point, std::optional<double> bearingDeg,
                                                           double radiusM)
{
  RoadPoint road;
  if (!m_route.SnapToRoad(point, ToCoreBearing(bearingDeg), radiusM, m_matchRoute, road.m_point, road.m_bearingDeg,
                          road.m_featureId, road.m_forward))
  {
    return {};
  }
  return road;
}

std::vector<RoadPoint> RoutingMapApi::RoutingRoads::SnapAll(ms::LatLon const & point, double bearingDeg, double radiusM)
{
  std::vector<RoadPoint> roads;
  for (auto const & [roadPoint, roadBearingDeg] : m_route.FindRoadsAlong(point, bearingDeg, radiusM))
    roads.push_back({roadPoint, roadBearingDeg});
  return roads;
}

std::vector<ms::LatLon> RoutingMapApi::RoutingRoads::FindCrossings(ms::LatLon const & center, double radiusM)
{
  return m_route.FindRoadCrossings(center, radiusM);
}

bool RoutingMapApi::RoutingRoads::IsRoundaboutNear(ms::LatLon const & center, double radiusM)
{
  return m_route.IsRoundaboutNear(center, radiusM);
}

RoutingMapApi::RoutingMapApi(SessionRoute & route, DataSource const & dataSource, Clock const & clock,
                             ShowCarHeadingFn && showCarHeading)
  : m_route(route)
  , m_dataSource(dataSource)
  , m_clock(clock)
  , m_showCarHeading(std::move(showCarHeading))
  , m_roads(route, false /* matchRoute */)
  , m_routeRoads(route, true /* matchRoute */)
{}

bool RoutingMapApi::IsNavigating() const
{
  return m_route.GetSession().IsActive() && m_route.GetSession().IsFollowing();
}

void RoutingMapApi::RebuildRouteIfOffRoute(Fix const & fix, double offRouteDistanceM, std::optional<double> bearingDeg)
{
  location::GpsInfo info;
  info.m_source = location::EUser;
  info.m_timestamp = m_clock.UnixNowMs() / 1000.0;
  info.m_latitude = fix.m_position.m_lat;
  info.m_longitude = fix.m_position.m_lon;
  info.m_horizontalAccuracy = offRouteDistanceM;
  info.m_bearing = ToCoreBearing(bearingDeg);
  m_route.RebuildRouteIfOffRoute(info);
}

void RoutingMapApi::LeaveRoute(Fix const & fix)
{
  location::GpsInfo info;
  info.m_source = location::EUser;
  info.m_timestamp = m_clock.UnixNowMs() / 1000.0;
  info.m_latitude = fix.m_position.m_lat;
  info.m_longitude = fix.m_position.m_lon;
  info.m_horizontalAccuracy = fix.m_accuracyM;
  info.m_bearing = ToCoreBearing(fix.m_bearingDeg);
  if (fix.m_speedMps)
    info.m_speed = *fix.m_speedMps;
  m_route.LeaveRoute(info);
}

std::optional<RoadPoint> RoutingMapApi::ProjectToRoute(ms::LatLon const & position, double radiusM)
{
  RoadPoint road;
  if (!m_route.ProjectToRoute(position, radiusM, road.m_point, road.m_bearingDeg))
    return {};
  return road;
}

std::optional<double> RoutingMapApi::GetRouteAside(ms::LatLon const & position, double bearingDeg)
{
  return m_route.GetRouteAside(position, bearingDeg);
}

std::optional<RoadWalker::Result> RoutingMapApi::ShiftAlongRoute(ms::LatLon const & position,
                                                                 std::optional<double> bearingDeg, double distanceM)
{
  RoadWalker::Result result;
  if (!m_route.ShiftAlongRoute(position, ToCoreBearing(bearingDeg), distanceM, result.m_position, result.m_bearingDeg,
                               result.m_appliedM, result.m_atCrossing))
  {
    return {};
  }
  return result;
}

routing::Maxspeed RoutingMapApi::GetMaxspeed(FeatureID const & road)
{
  if (!road.IsValid())
    return {};
  auto it = m_maxspeeds.find(road.m_mwmId);
  if (it == m_maxspeeds.end())
  {
    auto const handle = m_dataSource.GetMwmHandleById(road.m_mwmId);
    if (!handle.IsAlive())
      return {};
    it = m_maxspeeds.emplace(road.m_mwmId, routing::LoadMaxspeeds(handle)).first;
  }
  return it->second->GetMaxspeed(road.m_index);
}

std::optional<RoadPoint> RoutingMapApi::SnapToMainRoad(ms::LatLon const & position, double radiusM)
{
  RoadPoint road;
  if (!m_route.SnapToMainRoad(position, radiusM, road.m_point, road.m_bearingDeg))
    return {};
  return road;
}

std::string RoutingMapApi::GetDistanceLeft() const
{
  if (!m_route.GetSession().IsActive())
    return {};
  routing::FollowingInfo info;
  m_route.GetSession().GetRouteFollowingInfo(info);
  if (!info.IsValid())
    return {};
  return info.m_distToTarget.GetDistanceString() + info.m_distToTarget.GetUnitsString();
}

Service::Service(Delegate & delegate, SessionRoute & route, DataSource const & dataSource,
                 RoutingMapApi::ShowCarHeadingFn && showCarHeading)
  : m_map(route, dataSource, m_clock, std::move(showCarHeading))
  , m_engine(delegate, m_map, m_storage, m_clock, m_scheduler)
{}
}  // namespace nogps
