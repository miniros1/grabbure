cmake_minimum_required(VERSION 3.22)
project(grabbure CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT MINIROS_ANDROID_PREFIX)
  message(FATAL_ERROR "MINIROS_ANDROID_PREFIX is not set")
endif()
if(NOT ANDROID_ABI)
  message(FATAL_ERROR "ANDROID_ABI is not set")
endif()

set(miniros_DIR "${MINIROS_ANDROID_PREFIX}/${ANDROID_ABI}/lib/cmake/miniros")
find_package(Threads REQUIRED)
find_package(miniros REQUIRED)

add_library(grabbure SHARED
  native_bridge.cpp
  camera_publisher.cpp
  imu_publisher.cpp
  magnetometer_publisher.cpp
  gps_publisher.cpp
  sensors_bridge.cpp
  discovery_bridge.cpp
  log_forwarder.cpp)
target_include_directories(grabbure PRIVATE ${MINIROS_GENERATED_INCLUDE_DIRS})
target_link_libraries(grabbure PRIVATE miniros::roscxx log)
