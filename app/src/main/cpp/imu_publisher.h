#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include <miniros/publisher.h>
#include <miniros/ros.h>

namespace grabbure {

/// IMU topics on a child handle `imu`: `raw`, and `temp` when a temperature sensor exists.
/// `filtered` is advertised once a filter is publishing. Orientation is left unset.
class ImuPublisher {
 public:
  ImuPublisher() = default;

  void start(miniros::NodeHandle& node, bool temperature);
  void stop();

  /// Raw IMU topic, or empty when stopped.
  std::string topic() const;

  /// Temperature topic, or empty when that publisher is not advertised.
  std::string temperatureTopic() const;

  /// Advertised topics.
  std::vector<std::string> topics() const;

  /// Android device frame, rad/s. Publishes one raw Imu message per gyroscope sample.
  /// `bootNs` is `SensorEvent.timestamp`.
  bool pushGyroscope(double x, double y, double z, int64_t bootNs);

  /// Android device frame, m/s^2, including gravity.
  /// Held until the next gyroscope sample once a gyro sample has arrived.
  bool pushAccelerometer(double x, double y, double z, int64_t bootNs);

  /// Degrees Celsius. Variance is left unknown. `bootNs` is `SensorEvent.timestamp`.
  bool publishTemperature(double celsius, int64_t bootNs);

 private:
  bool publishLocked();

  std::string frameId_;
  mutable std::mutex mu_;
  miniros::Publisher pubRaw;
  miniros::Publisher pubFiltered;
  miniros::Publisher pubTemp;
  bool hasGyro_ = false;
  bool hasAccel_ = false;
  double gx_ = 0;
  double gy_ = 0;
  double gz_ = 0;
  double ax_ = 0;
  double ay_ = 0;
  double az_ = 0;
  int64_t stampBootNs_ = 0;
};

}  // namespace grabbure
