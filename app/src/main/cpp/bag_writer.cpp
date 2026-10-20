#include "bag_writer.h"

#include <array>

#include <minibag/bag.h>
#include <minibag/exceptions.h>

#include <miniros/rostime.h>

#include <sensor_msgs/CompressedImage.hxx>
#include <sensor_msgs/Imu.hxx>
#include <sensor_msgs/MagneticField.hxx>
#include <sensor_msgs/NavSatFix.hxx>
#include <sensor_msgs/Temperature.hxx>

namespace grabbure {
namespace {

minibag::Bag g_bag;

std::string namespaced(const std::string& phone, const std::string& rest) {
  return "/" + phone + "/" + rest;
}

void markAbsent(std::array<double, 9>& covariance) {
  covariance[0] = -1.0;
}

void ensureClock() {
  try {
    miniros::Time::now();
  } catch (const miniros::TimeNotInitializedException&) {
    miniros::Time::init();
  }
}

}  // namespace

std::string BagWriter::open(const std::string& path, const std::string& phone, const std::string& cameraId,
                            bool imu, bool temperature, bool magnetometer, bool gps) {
  std::lock_guard<std::mutex> lock(mu_);
  if (state_.open) {
    return "A bag is already open";
  }
  ensureClock();
  try {
    g_bag.open(path, minibag::bagmode::Write);
  } catch (const minibag::BagException& ex) {
    return ex.what();
  }
  const std::string name = phone.empty() ? "android" : phone;
  const std::string camera = std::string("camera") + (cameraId.empty() ? "0" : cameraId);
  state_ = State{};
  state_.open = true;
  if (!cameraId.empty()) {
    state_.cameraTopic = namespaced(name, camera + "/image_raw/compressed");
    state_.cameraFrame = name + "_" + camera;
  }
  if (imu) {
    state_.imuTopic = namespaced(name, "imu/raw");
    state_.imuFrame = name + "_imu";
    if (temperature) {
      state_.temperatureTopic = namespaced(name, "imu/temp");
    }
  }
  if (magnetometer) {
    state_.magnetometerTopic = namespaced(name, "mag/raw");
    state_.magnetometerFrame = name + "_mag";
  }
  if (gps) {
    state_.gpsTopic = namespaced(name, "gps");
    state_.gpsFrame = name + "_gps";
  }
  return {};
}

void BagWriter::close() {
  std::lock_guard<std::mutex> lock(mu_);
  if (!state_.open) {
    return;
  }
  try {
    g_bag.close();
  } catch (const minibag::BagException&) {
  }
  state_ = State{};
}

bool BagWriter::isOpen() const {
  std::lock_guard<std::mutex> lock(mu_);
  return state_.open;
}

std::string BagWriter::cameraTopic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return state_.cameraTopic;
}

std::string BagWriter::imuTopic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return state_.imuTopic;
}

std::string BagWriter::temperatureTopic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return state_.temperatureTopic;
}

std::string BagWriter::magnetometerTopic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return state_.magnetometerTopic;
}

std::string BagWriter::gpsTopic() const {
  std::lock_guard<std::mutex> lock(mu_);
  return state_.gpsTopic;
}

std::vector<std::string> BagWriter::topics() const {
  std::lock_guard<std::mutex> lock(mu_);
  std::vector<std::string> out;
  if (!state_.cameraTopic.empty()) {
    out.push_back(state_.cameraTopic);
  }
  if (!state_.imuTopic.empty()) {
    out.push_back(state_.imuTopic);
  }
  if (!state_.temperatureTopic.empty()) {
    out.push_back(state_.temperatureTopic);
  }
  if (!state_.magnetometerTopic.empty()) {
    out.push_back(state_.magnetometerTopic);
  }
  if (!state_.gpsTopic.empty()) {
    out.push_back(state_.gpsTopic);
  }
  return out;
}

bool BagWriter::writeJpeg(const uint8_t* data, int size) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!state_.open || state_.cameraTopic.empty() || !data || size <= 0) {
    return false;
  }
  sensor_msgs::CompressedImage msg;
  msg.header.stamp = miniros::Time::now();
  msg.header.frame_id = state_.cameraFrame;
  msg.format = "jpeg";
  msg.data.assign(data, data + size);
  try {
    g_bag.write(state_.cameraTopic, msg.header.stamp, msg);
  } catch (const minibag::BagException&) {
    return false;
  }
  return true;
}

bool BagWriter::pushGyroscope(double x, double y, double z) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!state_.open || state_.imuTopic.empty()) {
    return false;
  }
  state_.gx = x;
  state_.gy = y;
  state_.gz = z;
  state_.hasGyro = true;
  return writeImuLocked();
}

bool BagWriter::pushAccelerometer(double x, double y, double z) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!state_.open || state_.imuTopic.empty()) {
    return false;
  }
  state_.ax = x;
  state_.ay = y;
  state_.az = z;
  state_.hasAccel = true;
  if (state_.hasGyro) {
    return true;
  }
  return writeImuLocked();
}

bool BagWriter::writeImuLocked() {
  sensor_msgs::Imu msg;
  msg.header.stamp = miniros::Time::now();
  msg.header.frame_id = state_.imuFrame;
  markAbsent(msg.orientation_covariance);
  if (state_.hasGyro) {
    msg.angular_velocity.x = state_.gx;
    msg.angular_velocity.y = state_.gy;
    msg.angular_velocity.z = state_.gz;
  } else {
    markAbsent(msg.angular_velocity_covariance);
  }
  if (state_.hasAccel) {
    msg.linear_acceleration.x = state_.ax;
    msg.linear_acceleration.y = state_.ay;
    msg.linear_acceleration.z = state_.az;
  } else {
    markAbsent(msg.linear_acceleration_covariance);
  }
  try {
    g_bag.write(state_.imuTopic, msg.header.stamp, msg);
  } catch (const minibag::BagException&) {
    return false;
  }
  return true;
}

bool BagWriter::writeTemperature(double celsius) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!state_.open || state_.temperatureTopic.empty()) {
    return false;
  }
  sensor_msgs::Temperature msg;
  msg.header.stamp = miniros::Time::now();
  msg.header.frame_id = state_.imuFrame;
  msg.temperature = celsius;
  msg.variance = 0.0;
  try {
    g_bag.write(state_.temperatureTopic, msg.header.stamp, msg);
  } catch (const minibag::BagException&) {
    return false;
  }
  return true;
}

bool BagWriter::writeMagneticField(double xUt, double yUt, double zUt) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!state_.open || state_.magnetometerTopic.empty()) {
    return false;
  }
  sensor_msgs::MagneticField msg;
  msg.header.stamp = miniros::Time::now();
  msg.header.frame_id = state_.magnetometerFrame;
  msg.magnetic_field.x = xUt * 1e-6;
  msg.magnetic_field.y = yUt * 1e-6;
  msg.magnetic_field.z = zUt * 1e-6;
  try {
    g_bag.write(state_.magnetometerTopic, msg.header.stamp, msg);
  } catch (const minibag::BagException&) {
    return false;
  }
  return true;
}

bool BagWriter::writeNavSat(double latitudeDeg, double longitudeDeg, double altitudeM, bool hasAltitude,
                            double horizontalAccuracyM, double verticalAccuracyM) {
  std::lock_guard<std::mutex> lock(mu_);
  if (!state_.open || state_.gpsTopic.empty()) {
    return false;
  }
  sensor_msgs::NavSatFix msg;
  msg.header.stamp = miniros::Time::now();
  msg.header.frame_id = state_.gpsFrame;
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
  try {
    g_bag.write(state_.gpsTopic, msg.header.stamp, msg);
  } catch (const minibag::BagException&) {
    return false;
  }
  return true;
}

BagWriter& bagWriter() {
  static BagWriter writer;
  return writer;
}

}  // namespace grabbure
