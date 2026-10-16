#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include <miniros/publisher.h>
#include <miniros/ros.h>

#include <sensor_msgs/CompressedImage.hxx>
#include <sensor_msgs/Image.hxx>

namespace grabbure {

/// Publishes one camera. The sensor name is the topic prefix:
/// `<name>/image_raw`, `<name>/image_raw/compressed`, and later `<name>/camera_info`.
class CameraPublisher {
 public:
  explicit CameraPublisher(std::string sensorName = "android_camera");

  const std::string& sensorName() const { return sensorName_; }

  /// Advertise either the raw image or the compressed image. The other publisher is cleared.
  void start(miniros::NodeHandle& node, bool compressed);

  void stop();

  /// Active image topic, or empty when stopped.
  std::string topic() const;

  /// Advertised topics. Grows when raw, compressed, and camera info are all active.
  std::vector<std::string> topics() const;

  bool publishRaw(sensor_msgs::Image& msg);
  bool publishCompressed(sensor_msgs::CompressedImage& msg);

 private:
  /// Same header as the image. No-op until `pubInfo` is advertised.
  void publishInfo(const std_msgs::Header& header, uint32_t width, uint32_t height);

  std::string sensorName_;
  mutable std::mutex mu_;
  miniros::Publisher pubRaw;
  miniros::Publisher pubCompressed;
  miniros::Publisher pubInfo;
  std::string topic_;
};

}  // namespace grabbure
