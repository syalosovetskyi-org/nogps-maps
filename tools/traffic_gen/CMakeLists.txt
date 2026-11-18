project(traffic_gen)

omim_add_library(traffic_matcher traffic_matcher.cpp traffic_matcher.hpp)
target_link_libraries(traffic_matcher PUBLIC traffic)

omim_add_executable(traffic_gen traffic_gen.cpp)
target_link_libraries(traffic_gen PRIVATE traffic_matcher)

omim_add_test(traffic_gen_tests traffic_gen_tests.cpp)
if (BUILD_TESTING)
  target_link_libraries(traffic_gen_tests PRIVATE traffic_matcher)
endif()
