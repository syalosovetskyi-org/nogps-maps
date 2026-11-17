# Traffic feed, stage 1 (serg/tasks#3169)

This pipeline supplies the inherited traffic engine without changing the MWM or
traffic wire formats. Provider credentials are read only from `HERE_API_KEY` or
`TOMTOM_API_KEY`. No provider calls are needed for tests. Python 3.12 and uv are
sufficient: the scripts use only the standard library.

## Client format and routing

`TrafficInfo` requests `<base>/<MWM version>/<URL-encoded country name>.traffic`.
The version is the **MWM header's YYMMDD**, not the application version or current
date. Use the exact MWM distributed to clients: feature IDs and geometry change
between map builds. Spaces in filenames are normal; the HTTP server decodes them.

* Keys come from the `traffic` MWM section (`TRAFFIC_KEYS_FILE_TAG`), or, when it is
  absent, from the same URL with `.keys` appended: `name.traffic.keys`.
* Both `kLatestKeysVersion` and `kLatestValuesVersion` are **0**.
* Keys are **not compressed**. They start with a version byte and a varuint number
  of features, followed by bit-packed Elias gamma codes for feature-ID deltas + 1,
  segment counts + 1, and one one-way bit per feature. Decoding enumerates each
  feature's segment indices, forward then reverse for two-way roads. Ordering is
  `(feature ID, segment index, direction)`. Forward = 0, reverse = 1.
* Values are a **zlib stream**, not gzip. After inflation: version byte, varuint
  value count, then **3 bits per SpeedGroup, least significant bits first**, padded
  to a byte. Values are G0..G5 = 0..5, TempBlock = 6, Unknown = 7. Their count and
  order must exactly match the keys. There is no timestamp in this format.
* The generator uses `ExtractTrafficKeys`, `SerializeTrafficKeys` and
  `SerializeTrafficValues` themselves. If embedded keys exist, it checks that
  they equal the extracted keys before producing output.

The original fork returned `nullptr` from `CreateTrafficStash`, so production car
routes ignored traffic even though isolated routing tests used it. It now creates
an actual stash for cars. `TrafficStash::Guard` snapshots the routing session's
cache for each calculation. Enabling the layer starts downloads and feeds that
cache; disabling it clears the cache. Updates trigger route rebuilding.

Previously TempBlock multiplied time by 10000 (and another 1.8 penalty), so an
otherwise disconnected road could still be selected. Closed directed segments
are now filtered from normal and joint graph expansion, including partial edges
at the start/finish. Pedestrian/bicycle estimators remain unaffected. Ordinary
congestion still changes car weights using the existing group thresholds and
penalty. Cross-MWM leap selection uses precomputed weights; closures are applied
when expanding the real graph, but traffic-aware optimization of the leap
candidate selection is outside this stage.

## Build and normalize GeoJSON

Initialize the usual repository submodules and desktop dependencies first. Build
only the requested targets in a disposable directory:

```sh
cmake --preset debug -B build-traffic -DSKIP_QT_GUI=ON -DBUILD_DEV_SANDBOX=OFF
cmake --build build-traffic --target traffic traffic_gen traffic_tests traffic_gen_tests -j2
ctest --test-dir build-traffic --output-on-failure -R '^(traffic_tests|traffic_gen_tests)$'
```

`traffic_gen` is a desktop tool registered through `omim_add_tool_subdirectory`.
It is skipped on Android/iOS and when `SKIP_TOOLS` is enabled. Set
`MWM_RESOURCES_DIR=/absolute/path/to/repo/data` when running it away from the repo
or build directory, so the platform can find the classifier resources.

```sh
MWM_RESOURCES_DIR="$PWD/data" build-traffic/traffic_gen \
  '/maps/Ukraine_Kyiv.mwm' flow.geojson /srv/traffic
```

Input is a WGS84 GeoJSON `FeatureCollection` of `LineString` features:

```json
{"type":"FeatureCollection","features":[
  {"type":"Feature","geometry":{"type":"LineString","coordinates":[[30.52,50.45],[30.53,50.45]]},
   "properties":{"speed_ratio":0.25,"closed":false,"direction":"forward"}}
]}
```

`speed_ratio` is current/free speed in [0,1]. The upper percentage thresholds
`8,16,33,58,83,100` select G0..G5. Alternatively `jam_level` is an integer in [0,5],
where 0 = free flow (G5), 5 = worst congestion (G0). A ratio takes precedence over
a jam level. `closed=true` takes precedence over both and can appear alone.
Missing flow for an MWM segment becomes Unknown. Invalid input aborts with a
warning and nonzero exit code.

`direction` is `forward` (default, follows the source coordinate order),
`backward`, or `both`; it is not an MWM direction ID. Matching uses the road
segment midpoint, the nearest source subsegment within 15 metres, and a tangent
difference of at most 30 degrees. A spatial tree indexes source subsegments.
Reverse road keys reverse the road tangent. Ties use source input order.

Limits: this is a geometric proximity matcher, not topology/OpenLR matching.
Parallel streets, bridges, ramps, map/provider geometry differences, long MWM
segments crossing flow boundaries, and simplified source shapes can misassign
traffic. It has no elevation, road-name or road-class disambiguation. Measure
matched coverage and inspect both directions on real Ukrainian MWMs before
production use. Generation reads all MWM features twice; it has not been
benchmarked on the complete Ukraine maps here.

## Providers and quota

Default Kyiv bbox is **30.24,50.21,30.83,50.59** (west,south,east,north). All flow
outside it remains Unknown even when output targets all Ukraine MWMs.

* [HERE Traffic v7 flow](https://docs.here.com/traffic-api/reference/traffic-api-v7-getflow):
  one `/v7/flow` request per poll with `in=bbox:...` and
  `locationReferencing=shape`. Each shape link becomes a separate LineString;
  disconnected links are never joined. Use speed/freeFlow, and mark `closed`,
  `reversibleNotRoutable`, or jamFactor 10 as TempBlock. HERE jamFactor is 0..10
  and is **not** copied into our 0..5 `jam_level`. Source time is `sourceUpdated`.
  This stage uses aggregate `currentFlow`; it does not split the geometry by the
  optional `subSegments` linear offsets. See also the
  [flow semantics](https://docs.here.com/traffic-api/docs/flow).
* [TomTom relative Vector Flow Tiles v4](https://docs.tomtom.com/traffic-api/documentation/tomtom-maps/v1/traffic-flow/vector-flow-tiles):
  z10 covers this bbox with **6 tiles**: x598..599, y344..346. At one poll every
  five minutes that is **6 × 288 = 1728 requests/day**, leaving 772 of an assumed
  2500 daily request allowance. This is an arithmetic budget, not a guarantee of
  a particular account's current tariff. No automatic retries; a larger bbox
  requiring more than 8 tiles is rejected before making a request. A wider bbox
  30.20,50.20,30.85,50.65 would need 9 tiles (2592/day), exceeding that budget.
  Higher zoom improves geometry/local-road coverage but increases tile count.
  z10 is a first-stage quota compromise and does **not** promise every local road.
  [Flow Segment Data](https://docs.tomtom.com/traffic-api/documentation/tomtom-maps/v1/traffic-flow/flow-segment-data)
  returns only the road fragment nearest a query point. A 2500/day allowance
  supports at most 8 point queries per five-minute poll (2304/day), which cannot
  cover Kyiv's road network. Therefore tiles are used.

TomTom's `traffic_level` in relative mode supplies speed_ratio; `road_closure`
supplies closed. A small reader implements the needed fields of TomTom's
[published protobuf/MVT schema](https://docs.tomtom.com/traffic-api/documentation/tomtom-maps/v1/vector-tile-structure/vector-tile-structure).
It handles dictionary tags, float/double/bool values, extent, top-left origin,
zigzag/delta coordinates and multipart lines. Invalid tiles abort the poll.
`margin=0` avoids duplicated buffered shapes. `full` means a one-way road, not
that both travel directions should receive the same measurement.

Both adapters preserve shape order as travel direction. The public TomTom flow
page describes side coverage but does not explicitly guarantee travel direction
relative to vertex order; that interpretation must be confirmed with live data
before using its closures in production. Tiles have no provider update timestamp;
the metadata explicitly marks their timestamp as **acquisition time**. HERE's
provider timestamp is retained. Synthetic fixtures cannot prove coverage,
provider-side latency, directional interpretation, or account entitlements.

Run an adapter alone:

```sh
uv run --locked --project tools/traffic_feed python tools/traffic_feed/here.py --output flow.geojson
uv run --locked --project tools/traffic_feed python tools/traffic_feed/tomtom.py --output flow.geojson
```

Set the chosen provider key in the service environment. Request logs omit URLs,
keys and response bodies. HTTP auth/quota failures and network errors produce a
warning with provider/bbox/tile context and exit 1. A failed tile aborts the whole
poll. Empty results abort publication, retaining the previous snapshot until it
expires; they never refresh its timestamp.

## Polling, atomic publication and freshness

Schedule the following one-shot command every five minutes (cron/systemd timer).
Pass all client MWM files explicitly, including regional parts of Ukraine:

```sh
MWM_RESOURCES_DIR="$PWD/data" uv run --locked --project tools/traffic_feed \
  python tools/traffic_feed/run.py --provider here \
  --traffic-gen "$PWD/build-traffic/traffic_gen" \
  --mwm '/maps/Ukraine_Kyiv.mwm' '/maps/Ukraine_North.mwm' \
  --out /srv/traffic --max-age 900
```

`run.py` locks the output directory against overlapping polls, fetches once,
stages all MWM results, checks source age, and atomically replaces each file on
the destination filesystem. Keys are immutable within a version/name: different
keys for an already published map abort publication. Output:

```text
/srv/traffic/<MWM version>/<MWM name>.traffic
/srv/traffic/<MWM version>/<MWM name>.traffic.keys
/srv/traffic/<MWM version>/<MWM name>.traffic.json
```

The adjacent JSON records provider, source time, acquisition time, publish time,
timestamp kind, maximum age and SHA-256 hashes. It is replaced last; this is
atomic per file, not a transaction across all maps. The origin rejects a
values/metadata hash mismatch during replacement. A failure never updates the
source time. The client has no code to read these sidecars.

Use the included freshness-aware origin behind an HTTPS reverse proxy:

```sh
uv run --locked --project tools/traffic_feed python tools/traffic_feed/serve.py \
  --out /srv/traffic --bind 127.0.0.1 --port 8080
```

Run it as the publishing user or grant read access. Keep the reverse proxy from
caching `.traffic` responses: forward `Cache-Control: no-store` and status codes.
The origin returns 404 with body `0` when data are missing, stale (default 15 min),
or values/metadata are inconsistent. This is the existing client's NoData
protocol, and old rendering/routing colors are now cleared on NoData. The keys
and JSON sidecars remain available for inspection. A generic static file server
can serve the bytes, but **cannot enforce source freshness** by itself.

The inherited client state OUTDATED measures time since a successful HTTP
response (5 min + its update interval), not the provider's source age. A static
server repeatedly returning an old file with 200/304 would keep it looking fresh.
The freshness gate above avoids that without changing the binary protocol. If
the origin itself is unreachable, the inherited OUTDATED/network-error timing
still applies and cached traffic can temporarily remain in use.

## Build URL and CI integration (instructions only)

Desktop/native CMake:

```sh
cmake --preset debug -B build-traffic -DTRAFFIC_DATA_BASE_URL=https://traffic.example.org/
```

Android (the SDK Gradle module passes the property to native CMake):

```sh
cd android
./gradlew assembleGoogleDebug -Parm64 -PtrafficDataBaseUrl=https://traffic.example.org/
```

The default is empty and preserves the private.h/upstream behavior. A missing
trailing slash is added by the client. `private.h` is not edited. Android exposes
the Traffic menu item only when this property is nonempty. Both the regular and
car app flavors use the same shared layers menu. Native routing and Android
listeners are initialized already; the Activity now attaches/detaches its traffic
callback. NO_DATA, OUTDATED, network errors and expired maps/app show localized
messages (English/Russian/Ukrainian; other locales fall back to English). These
new Android XML files are hand-authored translation sources, not generated
`strings.xml` files. Attaching a callback now delivers its current state.

In **nogps-maps-ci**, add `-PtrafficDataBaseUrl=https://<your-origin>/` to the existing
Android Gradle invocation; for a native desktop build add
`-DTRAFFIC_DATA_BASE_URL=https://<your-origin>/` to its CMake configure command.
The app needs only this public origin URL; provider keys belong to the feed
service environment, never the mobile build. Build configurations must use the
same value for the SDK BuildConfig flag and native CMake argument (the Gradle
property handles both). No CI configuration has been changed in this branch.

## Tests and fixtures

```sh
uv run --offline --locked --project tools/traffic_feed --python /usr/bin/python3.12 \
  python -m unittest discover -s tools/traffic_feed/tests -v
```

Fixtures are **synthetic examples based on official documentation**, not recorded
live responses: there are no keys yet. `here.json` covers disjoint links and a
closure. `tomtom.json` is the human-readable description of `tomtom.pbf`; rebuild
it with `python3 tools/traffic_feed/tests/fixtures/make_tomtom.py`. Binary fixtures
include relative speed, closure, dictionary values, multipart lines, and tile
coordinates. Tests cover quota calculation, bad responses, redacted errors,
nonzero CLI failures, atomic replacements, hash mismatches and expiry.

Native tests in `traffic_gen_tests` cover ratio thresholds, jam levels, closure,
validation, nearest line, 14/16 m distance, 29/31 degree headings, reverse/both
directions, degenerate geometry, and actual TrafficInfo serializer/deserializer
round trips. `traffic_tests` also checks all-Unknown data becomes NoData.
Additional routing regressions in `applying_traffic_test.cpp` check that closures
on all alternatives return NoPath, both joint search directions reject a closed
segment, and a closed starting segment cannot escape
through a partial edge. Existing tests cover congestion detours and direction.
On a prepared CI worker, run these separately (routing dependencies are larger):

```sh
cmake --build build-traffic --target routing_tests -j2
build-traffic/routing_tests --filter='ApplyingTrafficTest.*' --data_path="$PWD/data"
```

### Validation on the task VPS

* Python 3.12 fixture tests run offline and pass; see `VALIDATION.md` for output.
* Actual desktop configure stops because Qt6 is absent. Boost/glaze and other
  submodule directories are also uninitialized. No full build or native test
  binary was run. The disposable build directory was removed.
* An isolated CMake smoke check verifies empty/configured URL compile definitions;
  this checks the option plumbing only, not the real traffic target.
* Required clang-format 23 is absent. The installed NDK clang-format 19 cannot
  read the repository's v23 configuration. Changed code was formatted with the
  compatible v19 settings; exact v23 style validation remains for CI.
* Android build, device UI, live APIs, real Ukraine MWM generation, and complete
  map/routing integration remain to be exercised on CI/devices after setup.
