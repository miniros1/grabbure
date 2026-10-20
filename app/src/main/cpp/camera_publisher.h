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

/// Publishes one camera on a child handle `cameraN`.
/// Image topics are `image_raw` and `image_raw/compressed`. `camera_info` is added when advertised.
class CameraPublisher {
 public:
  CameraPublisher() = default;

  /// Advertise either the raw image or the compressed image. The other publisher is cleared.
  void start(miniros::NodeHandle& node, const std::string& cameraId, bool compressed);

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

  std::string frameId_;
  mutable std::mutex mu_;
  miniros::Publisher pubRaw;
  miniros::Publisher pubCompressed;
  miniros::Publisher pubInfo;
};

}  // namespace grabbure
