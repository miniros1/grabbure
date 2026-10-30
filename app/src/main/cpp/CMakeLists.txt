cmake_minimum_required(VERSION 3.22)
project(grabbure CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT MINIROS_SOURCE_DIR)
  message(FATAL_ERROR "MINIROS_SOURCE_DIR is not set. Set miniros.dir in local.properties.")
endif()
if(NOT EXISTS "${MINIROS_SOURCE_DIR}/CMakeLists.txt")
  message(FATAL_ERROR "MINIROS_SOURCE_DIR is not a miniros source tree: ${MINIROS_SOURCE_DIR}")
endif()

# The NDK toolchain sets ANDROID. Miniros then builds static roscxx and bag_storage
# for this ABI. Console tools, tests, and examples stay out of the app build.
set(MINIROS_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(MINIROS_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory(${MINIROS_SOURCE_DIR} ${CMAKE_BINARY_DIR}/miniros EXCLUDE_FROM_ALL)

add_library(grabbure SHARED
  native_bridge.cpp
  camera_publisher.cpp
  imu_publisher.cpp
  magnetometer_publisher.cpp
  gps_publisher.cpp
  sensors_bridge.cpp
  discovery_bridge.cpp
  log_forwarder.cpp
  bag_writer.cpp)
target_link_libraries(grabbure PRIVATE roscxx bag_storage)
