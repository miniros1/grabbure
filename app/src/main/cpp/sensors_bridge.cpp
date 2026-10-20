#include "bag_writer.h"
#include "gps_publisher.h"
#include "imu_publisher.h"
#include "jni_util.h"
#include "magnetometer_publisher.h"
#include "motion_topics.h"
#include "node_session.h"

#include <jni.h>

#include <string>
#include <vector>

namespace {

grabbure::ImuPublisher g_imu;
grabbure::MagnetometerPublisher g_mag;
grabbure::GpsPublisher g_gps;

miniros::NodeHandle nodeOrNull(JNIEnv* env, jstring* error) {
  miniros::NodeHandle node;
  std::string message;
  if (!grabbure::copyNode(node, message)) {
    *error = grabbure::toJava(env, message);
  }
  return node;
}

}  // namespace

namespace grabbure {

std::vector<std::string> motionTopics() {
  std::vector<std::string> out = g_imu.topics();
  const std::vector<std::string> mag = g_mag.topics();
  const std::vector<std::string> gps = g_gps.topics();
  out.insert(out.end(), mag.begin(), mag.end());
  out.insert(out.end(), gps.begin(), gps.end());
  return out;
}

}  // namespace grabbure

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_startImu(JNIEnv* env, jclass, jboolean temperature) {
  jstring error = nullptr;
  miniros::NodeHandle node = nodeOrNull(env, &error);
  if (error) {
    return error;
  }
  try {
    g_imu.start(node, temperature == JNI_TRUE);
  } catch (const std::exception& ex) {
    return grabbure::toJava(env, ex.what());
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_miniros1_grabbure_NativeBridge_stopImu(JNIEnv*, jclass) {
  g_imu.stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_imuTopic(JNIEnv* env, jclass) {
  const std::string bag = grabbure::bagWriter().imuTopic();
  return grabbure::toJava(env, bag.empty() ? g_imu.topic() : bag);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_temperatureTopic(JNIEnv* env, jclass) {
  const std::string bag = grabbure::bagWriter().temperatureTopic();
  return grabbure::toJava(env, bag.empty() ? g_imu.temperatureTopic() : bag);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_miniros1_grabbure_NativeBridge_publishTemperature(JNIEnv*, jclass, jdouble celsius) {
  if (grabbure::bagWriter().isOpen()) {
    return grabbure::bagWriter().writeTemperature(celsius) ? JNI_TRUE : JNI_FALSE;
  }
  return g_imu.publishTemperature(celsius) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_miniros1_grabbure_NativeBridge_publishGyro(JNIEnv*, jclass, jdouble x, jdouble y, jdouble z) {
  if (grabbure::bagWriter().isOpen()) {
    return grabbure::bagWriter().pushGyroscope(x, y, z) ? JNI_TRUE : JNI_FALSE;
  }
  return g_imu.pushGyroscope(x, y, z) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_miniros1_grabbure_NativeBridge_publishAccel(JNIEnv*, jclass, jdouble x, jdouble y, jdouble z) {
  if (grabbure::bagWriter().isOpen()) {
    return grabbure::bagWriter().pushAccelerometer(x, y, z) ? JNI_TRUE : JNI_FALSE;
  }
  return g_imu.pushAccelerometer(x, y, z) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_startMagnetometer(JNIEnv* env, jclass) {
  jstring error = nullptr;
  miniros::NodeHandle node = nodeOrNull(env, &error);
  if (error) {
    return error;
  }
  try {
    g_mag.start(node);
  } catch (const std::exception& ex) {
    return grabbure::toJava(env, ex.what());
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_miniros1_grabbure_NativeBridge_stopMagnetometer(JNIEnv*, jclass) {
  g_mag.stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_magnetometerTopic(JNIEnv* env, jclass) {
  const std::string bag = grabbure::bagWriter().magnetometerTopic();
  return grabbure::toJava(env, bag.empty() ? g_mag.topic() : bag);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_miniros1_grabbure_NativeBridge_publishMagneticField(JNIEnv*, jclass, jdouble x, jdouble y,
                                                            jdouble z) {
  if (grabbure::bagWriter().isOpen()) {
    return grabbure::bagWriter().writeMagneticField(x, y, z) ? JNI_TRUE : JNI_FALSE;
  }
  return g_mag.publish(x, y, z) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_startGps(JNIEnv* env, jclass) {
  jstring error = nullptr;
  miniros::NodeHandle node = nodeOrNull(env, &error);
  if (error) {
    return error;
  }
  try {
    g_gps.start(node);
  } catch (const std::exception& ex) {
    return grabbure::toJava(env, ex.what());
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_miniros1_grabbure_NativeBridge_stopGps(JNIEnv*, jclass) {
  g_gps.stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_gpsTopic(JNIEnv* env, jclass) {
  const std::string bag = grabbure::bagWriter().gpsTopic();
  return grabbure::toJava(env, bag.empty() ? g_gps.topic() : bag);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_miniros1_grabbure_NativeBridge_publishNavSat(JNIEnv*, jclass, jdouble latitude, jdouble longitude,
                                                     jdouble altitude, jboolean hasAltitude,
                                                     jdouble horizontalAccuracyM, jdouble verticalAccuracyM) {
  if (grabbure::bagWriter().isOpen()) {
    return grabbure::bagWriter().writeNavSat(latitude, longitude, altitude, hasAltitude == JNI_TRUE,
                                             horizontalAccuracyM, verticalAccuracyM)
               ? JNI_TRUE
               : JNI_FALSE;
  }
  return g_gps.publish(latitude, longitude, altitude, hasAltitude == JNI_TRUE, horizontalAccuracyM,
                       verticalAccuracyM)
             ? JNI_TRUE
             : JNI_FALSE;
}
