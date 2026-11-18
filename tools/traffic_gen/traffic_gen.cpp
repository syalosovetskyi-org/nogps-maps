#include "traffic_matcher.hpp"

#include "traffic/traffic_info.hpp"

#include "platform/mwm_version.hpp"
#include "platform/platform.hpp"

#include "indexer/classificator_loader.hpp"
#include "indexer/feature_processor.hpp"

#include "coding/file_reader.hpp"
#include "coding/file_writer.hpp"
#include "coding/files_container.hpp"

#include "base/logging.hpp"

#include "defines.hpp"

#include <filesystem>
#include <iostream>
#include <stdexcept>

int main(int argc, char ** argv)
{
  if (argc != 4)
  {
    std::cerr << "Usage: traffic_gen MAP.mwm FLOW.geojson OUTPUT_ROOT\n"
              << "Writes <root>/<MWM version>/<MWM name>.traffic and .traffic.keys\n";
    return 2;
  }
  try
  {
    classificator::Load();
    std::string json;
    FileReader(argv[2]).ReadAsString(json);
    traffic_gen::Matcher const matcher(traffic_gen::ReadFlow(json));
    using Info = traffic::TrafficInfo;
    std::vector<Info::RoadSegmentId> keys;
    Info::ExtractTrafficKeys(argv[1], keys);
    FilesContainerR container(argv[1]);
    if (container.IsExist(TRAFFIC_KEYS_FILE_TAG))
    {
      auto reader = container.GetReader(TRAFFIC_KEYS_FILE_TAG);
      std::vector<uint8_t> bytes(reader.Size());
      reader.Read(0, bytes.data(), bytes.size());
      std::vector<Info::RoadSegmentId> embedded;
      Info::DeserializeTrafficKeys(bytes, embedded);
      if (embedded != keys)
        throw std::runtime_error("Embedded traffic keys differ from ExtractTrafficKeys");
    }

    std::vector<traffic::SpeedGroup> values(keys.size(), traffic::SpeedGroup::Unknown);
    size_t next = 0;
    feature::ForEachFeature(std::string(argv[1]), [&](FeatureType & feature, uint32_t fid)
    {
      if (next == keys.size() || keys[next].GetFid() != fid)
        return;
      feature.ParseGeometry(FeatureType::BEST_GEOMETRY);
      while (next < keys.size() && keys[next].GetFid() == fid)
      {
        auto const & key = keys[next];
        auto from = feature.GetPoint(key.GetIdx());
        auto to = feature.GetPoint(key.GetIdx() + 1);
        if (key.GetDir() == Info::RoadSegmentId::kReverseDirection)
          std::swap(from, to);
        values[next++] = matcher.Match(from, to);
      }
    });
    CHECK_EQUAL(next, keys.size(), ());
    auto const mwmVersion = version::MwmVersion::Read(container).GetVersion();
    auto const directory = std::filesystem::path(argv[3]) / std::to_string(mwmVersion);
    std::filesystem::create_directories(directory);
    auto const path = directory / (std::filesystem::path(argv[1]).stem().string() + ".traffic");
    std::vector<uint8_t> bytes;
    Info::SerializeTrafficKeys(keys, bytes);
    FileWriter(path.string() + ".keys").Write(bytes.data(), bytes.size());
    bytes.clear();
    Info::SerializeTrafficValues(values, bytes);
    FileWriter(path.string()).Write(bytes.data(), bytes.size());
    LOG(LINFO, ("Traffic generated", path.string(), "segments", keys.size()));
    return 0;
  }
  catch (std::exception const & e)
  {
    LOG(LWARNING, ("traffic_gen failed", argv[1], argv[2], e.what()));
    return 1;
  }
}
