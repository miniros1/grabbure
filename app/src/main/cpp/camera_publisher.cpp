#include "camera_publisher.h"

#include <sensor_msgs/CameraInfo.hxx>

namespace grabbure {

CameraPublisher::CameraPublisher(std::string sensorName)
  : sensorName_(std::move(sensorName)) {}

void CameraPublisher::start(miniros::NodeHandle& node, bool compressed) {
  std::lock_guard<std::mutex> lock(mu_);
  pubRaw = miniros::Publisher();
  pubCompressed = miniros::Publisher();
  if (compressed) {
    topic_ = sensorName_ + "/image_raw/compressed";
    pubCompressed = node.advertise<sensor_msgs::CompressedImage>(topic_, 1);
  } else {
    topic_ = sensorName_ + "/image_raw";
    pubRaw = node.advertise<sensor_msgs::Image>(topic_, 1);
  }
}

void CameraPublisher::stop() {
  std::lock_guard<std::mutex> lock(mu_);
  pubRaw = miniros::Publisher();
  pubCompressed = miniros::Publisher();
  pubInfo = miniros::Publisher();
  topic_.clear();
}

std::string CameraPublisher::topic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return topic_;
}

std::vector<std::string> CameraPublisher::topics() const {
  std::lock_guard<std::mutex> lock(mu_);
  std::vector<std::string> out;
  if (pubRaw) {
    out.push_back(sensorName_ + "/image_raw");
  }
  if (pubCompressed) {
    out.push_back(sensorName_ + "/image_raw/compressed");
  }
  if (pubInfo) {
    out.push_back(sensorName_ + "/camera_info");
  }
  return out;
}

bool CameraPublisher::publishRaw(sensor_msgs::Image& msg) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!pubRaw) {
    return false;
  }
  msg.header.stamp = miniros::Time::now();
  msg.header.frame_id = sensorName_;
  pubRaw.publish(msg);
  publishInfo(msg.header, msg.width, msg.height);
  return true;
}

bool CameraPublisher::publishCompressed(sensor_msgs::CompressedImage& msg) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!pubCompressed) {
    return false;
  }
  msg.header.stamp = miniros::Time::now();
  msg.header.frame_id = sensorName_;
  pubCompressed.publish(msg);
  publishInfo(msg.header, 0, 0);
  return true;
}

void CameraPublisher::publishInfo(const std_msgs::Header& header, uint32_t width, uint32_t height) {
  if (!pubInfo) {
    return;
  }
  sensor_msgs::CameraInfo info;
  info.header = header;
  info.width = width;
  info.height = height;
  pubInfo.publish(info);
}

}  // namespace grabbure
