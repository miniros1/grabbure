#include "camera_publisher.h"

#include "node_session.h"
#include "sensor_time.h"

#include <sensor_msgs/CameraInfo.hxx>

namespace grabbure {

void CameraPublisher::start(miniros::NodeHandle& node, const std::string& cameraId,
                            bool compressed) {
  std::lock_guard<std::mutex> lock(mu_);
  const std::string camera = std::string("camera") + (cameraId.empty() ? "0" : cameraId);
  miniros::NodeHandle sensor(node, camera);
  frameId_ = frameId(sensor);
  pubRaw = miniros::Publisher();
  pubCompressed = miniros::Publisher();
  if (compressed) {
    pubCompressed = sensor.advertise<sensor_msgs::CompressedImage>("image_raw/compressed", 1);
  } else {
    pubRaw = sensor.advertise<sensor_msgs::Image>("image_raw", 1);
  }
}

void CameraPublisher::stop() {
  std::lock_guard<std::mutex> lock(mu_);
  pubRaw = miniros::Publisher();
  pubCompressed = miniros::Publisher();
  pubInfo = miniros::Publisher();
  frameId_.clear();
}

std::string CameraPublisher::topic() const {
  std::lock_guard<std::mutex> lock(mu_);
  if (pubCompressed) {
    return pubCompressed.getTopic();
  }
  return pubRaw.getTopic();
}

std::vector<std::string> CameraPublisher::topics() const {
  std::lock_guard<std::mutex> lock(mu_);
  std::vector<std::string> out;
  if (pubRaw) {
    out.push_back(pubRaw.getTopic());
  }
  if (pubCompressed) {
    out.push_back(pubCompressed.getTopic());
  }
  if (pubInfo) {
    out.push_back(pubInfo.getTopic());
  }
  return out;
}

bool CameraPublisher::publishRaw(sensor_msgs::Image& msg, int64_t bootNs) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!pubRaw) {
    return false;
  }
  msg.header.stamp = timeFromBootNanos(bootNs);
  msg.header.frame_id = frameId_;
  pubRaw.publish(msg);
  publishInfo(msg.header, msg.width, msg.height);
  return true;
}

bool CameraPublisher::publishCompressed(sensor_msgs::CompressedImage& msg, int64_t bootNs) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!pubCompressed) {
    return false;
  }
  msg.header.stamp = timeFromBootNanos(bootNs);
  msg.header.frame_id = frameId_;
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
