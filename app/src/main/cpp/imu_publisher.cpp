#include "imu_publisher.h"

#include <array>

#include <sensor_msgs/Imu.hxx>

namespace grabbure {
namespace {

void markAbsent(std::array<double, 9>& covariance) {
  covariance[0] = -1.0;
}

}  // namespace

ImuPublisher::ImuPublisher(std::string sensorName)
  : sensorName_(std::move(sensorName)) {}

void ImuPublisher::start(miniros::NodeHandle& node) {
  std::lock_guard<std::mutex> lock(mu_);
  topic_ = sensorName_ + "/data";
  pubImu = node.advertise<sensor_msgs::Imu>(topic_, 100);
}

void ImuPublisher::stop() {
  std::lock_guard<std::mutex> lock(mu_);
  pubImu = miniros::Publisher();
  topic_.clear();
  hasGyro_ = false;
  hasAccel_ = false;
}

std::string ImuPublisher::topic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return topic_;
}

std::vector<std::string> ImuPublisher::topics() const {
  std::lock_guard<std::mutex> lock(mu_);
  if (!pubImu || topic_.empty()) {
    return {};
  }
  return {topic_};
}

bool ImuPublisher::pushGyroscope(double x, double y, double z) {
  std::lock_guard<std::mutex> lock(mu_);
  gx_ = x;
  gy_ = y;
  gz_ = z;
  hasGyro_ = true;
  return publishLocked();
}

bool ImuPublisher::pushAccelerometer(double x, double y, double z) {
  std::lock_guard<std::mutex> lock(mu_);
  ax_ = x;
  ay_ = y;
  az_ = z;
  hasAccel_ = true;
  if (hasGyro_) {
    return true;
  }
  return publishLocked();
}

bool ImuPublisher::publishLocked() {
  if (!pubImu) {
    return false;
  }
  sensor_msgs::Imu msg;
  msg.header.stamp = miniros::Time::now();
  msg.header.frame_id = sensorName_;
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
  pubImu.publish(msg);
  return true;
}

}  // namespace grabbure
