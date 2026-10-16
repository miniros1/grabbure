#pragma once

#include <mutex>
#include <string>
#include <vector>

#include <miniros/publisher.h>
#include <miniros/ros.h>

namespace grabbure {

/// Publishes one magnetometer as `sensor_msgs/MagneticField` on `<name>/data`.
/// Samples are stored here so filtering and calibration can run before publish.
/// Android reports microtesla and no covariance. The message field is tesla.
class MagnetometerPublisher {
 public:
  explicit MagnetometerPublisher(std::string sensorName = "android_magnetometer");

  const std::string& sensorName() const { return sensorName_; }

  void start(miniros::NodeHandle& node);
  void stop();

  /// Active topic, or empty when stopped.
  std::string topic() const;

  /// Advertised topics. Later this includes calibrated and raw field topics.
  std::vector<std::string> topics() const;

  /// Android device frame, microtesla.
  bool publish(double xUt, double yUt, double zUt);

 private:
  std::string sensorName_;
  mutable std::mutex mu_;
  miniros::Publisher pubField;
  std::string topic_;
  double xUt_ = 0;
  double yUt_ = 0;
  double zUt_ = 0;
};

}  // namespace grabbure
