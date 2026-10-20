#pragma once

#include <mutex>
#include <string>
#include <vector>

#include <miniros/publisher.h>
#include <miniros/ros.h>

namespace grabbure {

/// NavSatFix on the phone node as `gps` (`<phone>/gps`).
class GpsPublisher {
 public:
  GpsPublisher() = default;

  void start(miniros::NodeHandle& node);
  void stop();

  /// Topic, or empty when stopped.
  std::string topic() const;

  /// Advertised topics.
  std::vector<std::string> topics() const;

  /// WGS84 degrees and meters. A negative accuracy means that axis is unknown.
  bool publish(double latitudeDeg, double longitudeDeg, double altitudeM, bool hasAltitude,
               double horizontalAccuracyM, double verticalAccuracyM);

 private:
  std::string frameId_;
  mutable std::mutex mu_;
  miniros::Publisher pub;
};

}  // namespace grabbure
