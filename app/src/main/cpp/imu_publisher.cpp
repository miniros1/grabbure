#include "imu_publisher.h"

#include "node_session.h"
#include "sensor_time.h"

#include <array>

#include <sensor_msgs/Imu.hxx>
#include <sensor_msgs/Temperature.hxx>

namespace grabbure {
namespace {

void markAbsent(std::array<double, 9>& covariance) {
  covariance[0] = -1.0;
}

}  // namespace

void ImuPublisher::start(miniros::NodeHandle& node, bool temperature) {
  std::lock_guard<std::mutex> lock(mu_);
  miniros::NodeHandle sensor(node, "imu");
  frameId_ = frameId(sensor);
  pubRaw = sensor.advertise<sensor_msgs::Imu>("raw", 100);
  pubFiltered = miniros::Publisher();
  pubTemp = miniros::Publisher();
  if (temperature) {
    pubTemp = sensor.advertise<sensor_msgs::Temperature>("temp", 10);
  }
}

void ImuPublisher::stop() {
  std::lock_guard<std::mutex> lock(mu_);
  pubRaw = miniros::Publisher();
  pubFiltered = miniros::Publisher();
  pubTemp = miniros::Publisher();
  frameId_.clear();
  hasGyro_ = false;
  hasAccel_ = false;
}

std::string ImuPublisher::topic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return pubRaw.getTopic();
}

std::string ImuPublisher::temperatureTopic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return pubTemp.getTopic();
}

std::vector<std::string> ImuPublisher::topics() const {
  std::lock_guard<std::mutex> lock(mu_);
  std::vector<std::string> out;
  if (pubRaw) {
    out.push_back(pubRaw.getTopic());
  }
  if (pubFiltered) {
    out.push_back(pubFiltered.getTopic());
  }
  if (pubTemp) {
    out.push_back(pubTemp.getTopic());
  }
  return out;
}

bool ImuPublisher::pushGyroscope(double x, double y, double z, int64_t bootNs) {
  std::lock_guard<std::mutex> lock(mu_);
  gx_ = x;
  gy_ = y;
  gz_ = z;
  stampBootNs_ = bootNs;
  hasGyro_ = true;
  return publishLocked();
}

bool ImuPublisher::pushAccelerometer(double x, double y, double z, int64_t bootNs) {
  std::lock_guard<std::mutex> lock(mu_);
  ax_ = x;
  ay_ = y;
  az_ = z;
  hasAccel_ = true;
  if (hasGyro_) {
    return true;
  }
  stampBootNs_ = bootNs;
  return publishLocked();
}

bool ImuPublisher::publishTemperature(double celsius, int64_t bootNs) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!pubTemp) {
    return false;
  }
  sensor_msgs::Temperature msg;
  msg.header.stamp = timeFromBootNanos(bootNs);
  msg.header.frame_id = frameId_;
  msg.temperature = celsius;
  msg.variance = 0.0;
  pubTemp.publish(msg);
  return true;
}

bool ImuPublisher::publishLocked() {
  if (!pubRaw) {
    return false;
  }
  sensor_msgs::Imu msg;
  msg.header.stamp = timeFromBootNanos(stampBootNs_);
  msg.header.frame_id = frameId_;
  // No orientation estimate. A zero quaternion would look like identity.
  markAbsent(msg.orientation_covariance);
  if (hasGyro_) {
    msg.angular_velocity.x = gx_;
    msg.angular_velocity.y = gy_;
    msg.angular_velocity.z = gz_;
  } else {
    markAbsent(msg.angular_velocity_covariance);
  }
  if (hasAccel_) {
    msg.linear_acceleration.x = ax_;
    msg.linear_acceleration.y = ay_;
    msg.linear_acceleration.z = az_;
  } else {
    markAbsent(msg.linear_acceleration_covariance);
  }
  pubRaw.publish(msg);
  return true;
}

}  // namespace grabbure
