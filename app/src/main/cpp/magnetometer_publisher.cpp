#include "magnetometer_publisher.h"

#include <sensor_msgs/MagneticField.hxx>

namespace grabbure {
namespace {

constexpr double kMicroteslaToTesla = 1e-6;

}  // namespace

MagnetometerPublisher::MagnetometerPublisher(std::string sensorName)
  : sensorName_(std::move(sensorName)) {}

void MagnetometerPublisher::start(miniros::NodeHandle& node) {
  std::lock_guard<std::mutex> lock(mu_);
  topic_ = sensorName_ + "/data";
  pubField = node.advertise<sensor_msgs::MagneticField>(topic_, 50);
}

void MagnetometerPublisher::stop() {
  std::lock_guard<std::mutex> lock(mu_);
  pubField = miniros::Publisher();
  topic_.clear();
}

std::string MagnetometerPublisher::topic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return topic_;
}

std::vector<std::string> MagnetometerPublisher::topics() const {
  std::lock_guard<std::mutex> lock(mu_);
  if (!pubField || topic_.empty()) {
    return {};
  }
  return {topic_};
}

bool MagnetometerPublisher::publish(double xUt, double yUt, double zUt) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!pubField) {
    return false;
  }
  xUt_ = xUt;
  yUt_ = yUt;
  zUt_ = zUt;
  sensor_msgs::MagneticField msg;
  msg.header.stamp = miniros::Time::now();
  msg.header.frame_id = sensorName_;
  msg.magnetic_field.x = xUt_ * kMicroteslaToTesla;
  msg.magnetic_field.y = yUt_ * kMicroteslaToTesla;
  msg.magnetic_field.z = zUt_ * kMicroteslaToTesla;
  pubField.publish(msg);
  return true;
}

}  // namespace grabbure
