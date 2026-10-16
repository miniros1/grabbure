#pragma once

#include <mutex>
#include <string>
#include <vector>

#include <miniros/publisher.h>
#include <miniros/ros.h>

namespace grabbure {

/// Publishes one IMU as `sensor_msgs/Imu` on `<name>/data`.
/// Samples are stored here so filtering and calibration can run before publish.
/// Orientation is left unset. Android does not report covariances.
class ImuPublisher {
 public:
  explicit ImuPublisher(std::string sensorName = "android_imu");

  const std::string& sensorName() const { return sensorName_; }

  void start(miniros::NodeHandle& node);
  void stop();

  /// Active topic, or empty when stopped.
  std::string topic() const;

  /// Advertised topics. Later this includes calibrated, raw, and temperature.
  std::vector<std::string> topics() const;

  /// Android device frame, rad/s. Publishes one Imu message per gyroscope sample.
  bool pushGyroscope(double x, double y, double z);

  /// Android device frame, m/s^2, including gravity.
  /// Held until the next gyroscope sample once a gyro sample has arrived.
  bool pushAccelerometer(double x, double y, double z);

 private:
  bool publishLocked();

  std::string sensorName_;
  mutable std::mutex mu_;
  miniros::Publisher pubImu;
  std::string topic_;
  bool hasGyro_ = false;
  bool hasAccel_ = false;
  double gx_ = 0;
  double gy_ = 0;
  double gz_ = 0;
  double ax_ = 0;
  double ay_ = 0;
  double az_ = 0;
};

}  // namespace grabbure
