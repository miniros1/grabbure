#include "gps_publisher.h"

#include "node_session.h"
#include "sensor_time.h"

#include <sensor_msgs/NavSatFix.hxx>

namespace grabbure {

void GpsPublisher::start(miniros::NodeHandle& node) {
  std::lock_guard<std::mutex> lock(mu_);
  frameId_ = frameId(node);
  if (!frameId_.empty()) {
    frameId_ += "_";
  }
  frameId_ += "gps";
  pub = node.advertise<sensor_msgs::NavSatFix>("gps", 10);
}

void GpsPublisher::stop() {
  std::lock_guard<std::mutex> lock(mu_);
  pub = miniros::Publisher();
  frameId_.clear();
}

std::string GpsPublisher::topic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return pub.getTopic();
}

std::vector<std::string> GpsPublisher::topics() const {
  std::lock_guard<std::mutex> lock(mu_);
  std::vector<std::string> out;
  if (pub) {
    out.push_back(pub.getTopic());
  }
  return out;
}

bool GpsPublisher::publish(double latitudeDeg, double longitudeDeg, double altitudeM, bool hasAltitude,
                           double horizontalAccuracyM, double verticalAccuracyM, int64_t bootNs) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!pub) {
    return false;
  }
  sensor_msgs::NavSatFix msg;
  msg.header.stamp = timeFromBootNanos(bootNs);
  msg.header.frame_id = frameId_;
  msg.status.status = sensor_msgs::NavSatStatus::STATUS_FIX;
  msg.status.service = sensor_msgs::NavSatStatus::SERVICE_GPS;
  msg.latitude = latitudeDeg;
  msg.longitude = longitudeDeg;
  msg.altitude = hasAltitude ? altitudeM : 0.0;
  msg.position_covariance.fill(0.0);
  const bool hasHorizontal = horizontalAccuracyM > 0.0;
  const bool hasVertical = verticalAccuracyM > 0.0;
  if (hasHorizontal) {
    const double variance = horizontalAccuracyM * horizontalAccuracyM;
    msg.position_covariance[0] = variance;
    msg.position_covariance[4] = variance;
  }
  if (hasVertical) {
    msg.position_covariance[8] = verticalAccuracyM * verticalAccuracyM;
  }
  if (hasHorizontal && hasVertical) {
    msg.position_covariance_type = sensor_msgs::NavSatFix::COVARIANCE_TYPE_DIAGONAL_KNOWN;
  } else if (hasHorizontal) {
    msg.position_covariance_type = sensor_msgs::NavSatFix::COVARIANCE_TYPE_APPROXIMATED;
  } else {
    msg.position_covariance_type = sensor_msgs::NavSatFix::COVARIANCE_TYPE_UNKNOWN;
  }
  pub.publish(msg);
  return true;
}

}  // namespace grabbure
