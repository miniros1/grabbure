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
  out.insert(out.end(), mag.begin(), mag.end());
  return out;
}

}  // namespace grabbure

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_startImu(JNIEnv* env, jclass) {
  jstring error = nullptr;
  miniros::NodeHandle node = nodeOrNull(env, &error);
  if (error) {
    return error;
  }
  g_imu.start(node);
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dkargin_grabbure_NativeBridge_stopImu(JNIEnv*, jclass) {
  g_imu.stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_imuTopic(JNIEnv* env, jclass) {
  return grabbure::toJava(env, g_imu.topic());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dkargin_grabbure_NativeBridge_publishGyro(JNIEnv*, jclass, jdouble x, jdouble y, jdouble z) {
  return g_imu.pushGyroscope(x, y, z) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dkargin_grabbure_NativeBridge_publishAccel(JNIEnv*, jclass, jdouble x, jdouble y, jdouble z) {
  return g_imu.pushAccelerometer(x, y, z) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_startMagnetometer(JNIEnv* env, jclass) {
  jstring error = nullptr;
  miniros::NodeHandle node = nodeOrNull(env, &error);
  if (error) {
    return error;
  }
  g_mag.start(node);
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dkargin_grabbure_NativeBridge_stopMagnetometer(JNIEnv*, jclass) {
  g_mag.stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_magnetometerTopic(JNIEnv* env, jclass) {
  return grabbure::toJava(env, g_mag.topic());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dkargin_grabbure_NativeBridge_publishMagneticField(JNIEnv*, jclass, jdouble x, jdouble y,
                                                            jdouble z) {
  return g_mag.publish(x, y, z) ? JNI_TRUE : JNI_FALSE;
}
