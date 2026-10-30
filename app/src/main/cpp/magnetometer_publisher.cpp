#include "magnetometer_publisher.h"

#include "node_session.h"
#include "sensor_time.h"

#include <sensor_msgs/MagneticField.hxx>

namespace grabbure {
namespace {

constexpr double kMicroteslaToTesla = 1e-6;

}  // namespace

void MagnetometerPublisher::start(miniros::NodeHandle& node) {
  std::lock_guard<std::mutex> lock(mu_);
  miniros::NodeHandle sensor(node, "mag");
  frameId_ = frameId(sensor);
  pubRaw = sensor.advertise<sensor_msgs::MagneticField>("raw", 50);
  pubFiltered = miniros::Publisher();
}

void MagnetometerPublisher::stop() {
  std::lock_guard<std::mutex> lock(mu_);
  pubRaw = miniros::Publisher();
  pubFiltered = miniros::Publisher();
  frameId_.clear();
}

std::string MagnetometerPublisher::topic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return pubRaw.getTopic();
}

std::vector<std::string> MagnetometerPublisher::topics() const {
  std::lock_guard<std::mutex> lock(mu_);
  std::vector<std::string> out;
  if (pubRaw) {
    out.push_back(pubRaw.getTopic());
  }
  if (pubFiltered) {
    out.push_back(pubFiltered.getTopic());
  }
  return out;
}

bool MagnetometerPublisher::publish(double xUt, double yUt, double zUt, int64_t bootNs) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!pubRaw) {
    return false;
  }
  xUt_ = xUt;
  yUt_ = yUt;
  zUt_ = zUt;
  sensor_msgs::MagneticField msg;
  msg.header.stamp = timeFromBootNanos(bootNs);
  msg.header.frame_id = frameId_;
  msg.magnetic_field.x = xUt_ * kMicroteslaToTesla;
  msg.magnetic_field.y = yUt_ * kMicroteslaToTesla;
  msg.magnetic_field.z = zUt_ * kMicroteslaToTesla;
  pubRaw.publish(msg);
  return true;
}

}  // namespace grabbure
