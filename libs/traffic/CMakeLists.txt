project(traffic)

set(SRC
  speed_groups.cpp
  speed_groups.hpp
  traffic_cache.cpp
  traffic_cache.hpp
  traffic_info.cpp
  traffic_info.hpp
)

omim_add_library(${PROJECT_NAME} ${SRC})

set(TRAFFIC_DATA_BASE_URL "" CACHE STRING "Traffic origin URL; empty keeps the private.h default")
if (TRAFFIC_DATA_BASE_URL)
  if (NOT TRAFFIC_DATA_BASE_URL MATCHES "^https?://" OR TRAFFIC_DATA_BASE_URL MATCHES "[\";\\\\\n\r ]")
    message(FATAL_ERROR "TRAFFIC_DATA_BASE_URL must be an HTTP(S) URL without quotes, whitespace or semicolons")
  endif()
  target_compile_definitions(traffic PRIVATE NOGPS_TRAFFIC_DATA_BASE_URL="${TRAFFIC_DATA_BASE_URL}")
endif()

target_link_libraries(${PROJECT_NAME}
  PUBLIC
    routing_common
    indexer
)

omim_add_test_subdirectory(traffic_tests)
