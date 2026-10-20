#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace grabbure {

/// Writes sensor messages into a rosbag with no node and no network transport.
class BagWriter {
 public:
  /// Opens `path` for writing. Topics follow `<phone>/...`. Camera images are JPEG only.
  std::string open(const std::string& path, const std::string& phone, const std::string& cameraId,
                   bool imu, bool temperature, bool magnetometer, bool gps);

  void close();

  bool isOpen() const;

  std::string cameraTopic() const;
  std::string imuTopic() const;
  std::string temperatureTopic() const;
  std::string magnetometerTopic() const;
  std::string gpsTopic() const;
  std::vector<std::string> topics() const;

  bool writeJpeg(const uint8_t* data, int size);
  bool pushGyroscope(double x, double y, double z);
  bool pushAccelerometer(double x, double y, double z);
  bool writeTemperature(double celsius);
  bool writeMagneticField(double xUt, double yUt, double zUt);
  bool writeNavSat(double latitudeDeg, double longitudeDeg, double altitudeM, bool hasAltitude,
                   double horizontalAccuracyM, double verticalAccuracyM);

 private:
  struct State {
    bool open = false;
    std::string cameraTopic;
    std::string cameraFrame;
    std::string imuTopic;
    std::string imuFrame;
    std::string temperatureTopic;
    std::string magnetometerTopic;
    std::string magnetometerFrame;
    std::string gpsTopic;
    std::string gpsFrame;
    bool hasGyro = false;
    bool hasAccel = false;
    double gx = 0;
    double gy = 0;
    double gz = 0;
    double ax = 0;
    double ay = 0;
    double az = 0;
  };

  bool writeImuLocked();

  mutable std::mutex mu_;
  State state_;
};

BagWriter& bagWriter();

}  // namespace grabbure
