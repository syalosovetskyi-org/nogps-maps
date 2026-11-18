# Validation on 2026-11-17

No provider API was contacted. No full project or Android build was run. No CI
configuration was modified, and nothing was pushed or merged.

## Python fixtures: PASS (exit 0)

```sh
UV_CACHE_DIR="$PWD/.traffic-uv-cache" uv run --offline --locked \
  --project tools/traffic_feed --python /usr/bin/python3.12 \
  python -m unittest discover -s tools/traffic_feed/tests -v
```

```text
test_here_fixture (test_feed.FeedTests.test_here_fixture) ... ok
test_here_reversible_and_invalid (test_feed.FeedTests.test_here_reversible_and_invalid) ... ok
test_missing_key_nonzero_warning (test_feed.FeedTests.test_missing_key_nonzero_warning) ... ok
test_publish_and_freshness (test_feed.FeedTests.test_publish_and_freshness) ... ok
test_request_parameters_and_no_secret_in_errors (test_feed.FeedTests.test_request_parameters_and_no_secret_in_errors) ... ok
test_tile_quota (test_feed.FeedTests.test_tile_quota) ... ok
test_tomtom_binary_fixture (test_feed.FeedTests.test_tomtom_binary_fixture) ... ok
test_tomtom_empty_and_corrupt (test_feed.FeedTests.test_tomtom_empty_and_corrupt) ... ok
test_mvt_truncation_and_negative_deltas (test_feed.OriginAndPipelineTests.test_mvt_truncation_and_negative_deltas) ... ok
test_origin_status_and_url_decoding (test_feed.OriginAndPipelineTests.test_origin_status_and_url_decoding) ... ok
test_poll_generation_failure_preserves_publication (test_feed.OriginAndPipelineTests.test_poll_generation_failure_preserves_publication) ... INFO:traffic_feed:Published 260101/Ukraine.traffic source_updated=2026-11-17T06:39:42.734383+00:00
ok

----------------------------------------------------------------------
Ran 11 tests in 0.343s

OK

```

The pipeline test substitutes the generator process with fixture output. It
validates orchestration and preservation on failure, not the native generator.
The origin test runs the actual HTTP handler without opening a listening socket.
The fixture builder produces the same SHA-256 before/after regeneration:
`2929d61b98dc32ac616170292e1a2970e2ce8ca10c20a50ed726d948a03beb00`.
All three Android translation XML files parse (15 strings total).

## Actual native configure: BLOCKED (exit 1)

The default PATH has no `cmake`; the Android SDK contains a CMake executable.

```sh
/opt/android-sdk/cmake/3.22.1/bin/cmake --preset debug -B build-codex-traffic \
  -DCMAKE_MAKE_PROGRAM=/opt/android-sdk/cmake/3.22.1/bin/ninja \
  -DSKIP_QT_GUI=ON -DBUILD_DEV_SANDBOX=OFF -DUSE_CCACHE=OFF
```

```text
CMake Error at CMakeLists.txt:167 (find_package):
  Could not find a package configuration file provided by "Qt6" with any of
  the following names:

    Qt6Config.cmake
    qt6-config.cmake

  Add the installation prefix of "Qt6" to CMAKE_PREFIX_PATH or set "Qt6_DIR"
  to a directory containing one of the above files.  If "Qt6" provides a
  separate development package or SDK, be sure it has been installed.


-- Configuring incomplete, errors occurred!
See also "/root/work/nogps-full-nogps-traffic-3169/build-codex-traffic/CMakeFiles/CMakeOutput.log".

```

The checkout also has uninitialized required submodules, including Boost/glaze.
An attempted syntax-only check confirms the first missing native header:

```sh
g++ -std=c++23 -fsyntax-only -Ilibs -I. -I3party/boost_headers libs/routing/edge_estimator.cpp
```

```text
In file included from libs/routing/road_point.hpp:8,
                 from libs/routing/segment.hpp:3,
                 from libs/routing/edge_estimator.hpp:3,
                 from libs/routing/edge_estimator.cpp:1:
libs/std/boost_container_hash.hpp:14:10: fatal error: boost/container_hash/hash.hpp: No such file or directory
   14 | #include <boost/container_hash/hash.hpp>
      |          ^~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
compilation terminated.

```

Consequently **traffic, traffic_gen, traffic_tests, traffic_gen_tests and routing
regressions have not been compiled or executed**. The disposable build directory
was removed. Repeat the targeted commands in README.md on a prepared CI worker.

## Isolated CMake option smoke check: PASS

A temporary project included the real `libs/traffic/CMakeLists.txt` with a dummy
library, avoiding all map/Qt dependencies. Default configuration yielded
`DEFINITIONS-NOTFOUND`; configuration with
`-DTRAFFIC_DATA_BASE_URL=https://traffic.example.test/feed` yielded:

```text
-- Traffic definitions: NOGPS_TRAFFIC_DATA_BASE_URL="https://traffic.example.test/feed"
[1/2] Building CXX object CMakeFiles/traffic.dir/dummy.cpp.o
[2/2] Linking CXX static library libtraffic.a
```

Both configurations exited 0; compiling the configured string macro exited 0.
An origin containing a space was rejected with exit 1 and the intended diagnostic.
This **does not** mean the real traffic library has compiled. All smoke-check
files/build directories were removed.

## Style check

`git diff --check`: exit 0.
Required clang-format 23 is unavailable. The installed NDK formatter is 19.0.1;
with the original repository configuration its dry run exits 1:

```text
.clang-format:7:23: error: unknown enumerated scalar
AlignEscapedNewlines: LeftWithLastLine
```

New code and changed C++/Java lines were formatted with the v19-compatible
settings (LeftWithLastLine -> Left; omit BinPackLongBracedList;
BinPackParameters -> true). Exact v23 checking remains for CI.

## External dependencies and remaining verification

The first implementation tried to use a PyPI MVT decoder; its download failed
under network sandboxing, and the escalation request was rejected. The final
implementation removed that dependency and uses its tested standard-library
MVT reader. Final uv tests run offline and need no downloads.

Remaining: actual native compilation/tests; Android assembly and device UI;
live HERE/TomTom schema/direction/account checks; real Ukrainian MWM generation,
coverage, memory/runtime measurements and route/renderer integration. The bbox
and z10 tile budget deliberately trade local-road coverage for request count.
See README.md for detailed matching, direction, timestamp and cross-MWM risks.

## Local commits: BLOCKED

`git add` exited 128:

```text
fatal: Unable to create '/root/work/nogps-full/.git/worktrees/nogps-full-nogps-traffic-3169/index.lock': Read-only file system
```

The escalation request for writing the worktree index was rejected without an
additional reason. No commits were created; all changes remain in the working
tree for review. No attempt was made to bypass this restriction.
