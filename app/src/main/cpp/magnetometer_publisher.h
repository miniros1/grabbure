#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include <miniros/publisher.h>
#include <miniros/ros.h>

namespace grabbure {

/// Magnetometer topics on a child handle `mag`: `raw` now, `filtered` once a filter publishes.
/// Android reports microtesla and no covariance. The message field is tesla.
class MagnetometerPublisher {
 public:
  MagnetometerPublisher() = default;

  void start(miniros::NodeHandle& node);
  void stop();

  /// Raw topic, or empty when stopped.
  std::string topic() const;

  /// Advertised topics.
  std::vector<std::string> topics() const;

  /// Android device frame, microtesla. `bootNs` is `SensorEvent.timestamp`.
  bool publish(double xUt, double yUt, double zUt, int64_t bootNs);

 private:
  std::string frameId_;
  mutable std::mutex mu_;
  miniros::Publisher pubRaw;
  miniros::Publisher pubFiltered;
  double xUt_ = 0;
  double yUt_ = 0;
  double zUt_ = 0;
};

}  // namespace grabbure
