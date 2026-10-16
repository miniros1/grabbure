#include "camera_publisher.h"
#include "jni_util.h"
#include "motion_topics.h"
#include "node_session.h"

#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <vector>

#include <miniros/datatypes.h>
#include <miniros/ros.h>

namespace {

std::mutex g_nodeMu;
std::unique_ptr<miniros::NodeHandle> g_node;
std::string g_masterUri;
grabbure::CameraPublisher g_camera;

const uint8_t* bufferData(JNIEnv* env, jobject buffer, int* available) {
  if (!buffer) {
    return nullptr;
  }
  void* base = env->GetDirectBufferAddress(buffer);
  if (!base) {
    return nullptr;
  }
  jclass cls = env->GetObjectClass(buffer);
  jmethodID position = env->GetMethodID(cls, "position", "()I");
  jmethodID limit = env->GetMethodID(cls, "limit", "()I");
  if (!position || !limit) {
    return nullptr;
  }
  const int pos = env->CallIntMethod(buffer, position);
  const int lim = env->CallIntMethod(buffer, limit);
  if (pos < 0 || lim < pos) {
    return nullptr;
  }
  *available = lim - pos;
  return static_cast<const uint8_t*>(base) + pos;
}

bool appendPlane(std::vector<uint8_t>& out, const uint8_t* data, int available,
                 int rowStride, int pixelStride, int width, int height) {
  if (!data || width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) {
    return false;
  }
  const int need = rowStride * (height - 1) + pixelStride * (width - 1) + 1;
  if (need > available) {
    return false;
  }
  out.reserve(out.size() + static_cast<size_t>(width) * static_cast<size_t>(height));
  for (int row = 0; row < height; ++row) {
    const uint8_t* src = data + row * rowStride;
    if (pixelStride == 1) {
      out.insert(out.end(), src, src + width);
    } else {
      for (int col = 0; col < width; ++col) {
        out.push_back(src[col * pixelStride]);
      }
    }
  }
  return true;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_nodeStart(JNIEnv* env, jclass, jstring masterUri, jstring ip,
                                                  jstring filesDir) {
  const std::string uri = grabbure::fromJava(env, masterUri);
  const std::string address = grabbure::fromJava(env, ip);
  const std::string files = grabbure::fromJava(env, filesDir);
  if (uri.empty() || address.empty() || files.empty()) {
    return grabbure::toJava(env, "Master URI, phone address, and files directory are required");
  }

  std::lock_guard<std::mutex> lock(g_nodeMu);
  if (miniros::isInitialized()) {
    if (g_masterUri != uri) {
      return grabbure::toJava(env, "This process is already connected to " + g_masterUri +
                                       ". Restart the app to use another master.");
    }
    return nullptr;
  }

  const std::string logDir = files + "/log";
  mkdir(logDir.c_str(), 0755);
  setenv("HOME", files.c_str(), 1);
  setenv("ROS_LOG_DIR", logDir.c_str(), 1);
  setenv("ROS_IP", address.c_str(), 1);
  setenv("ROS_MASTER_URI", uri.c_str(), 1);

  miniros::M_string remaps;
  remaps["__master"] = uri;
  remaps["__ip"] = address;
  try {
    miniros::init(remaps, "grabbure",
                  miniros::init_options::NoSigintHandler | miniros::init_options::NoRosout);
    g_node.reset(new miniros::NodeHandle());
    g_masterUri = uri;
  } catch (const std::exception& ex) {
    return grabbure::toJava(env, ex.what());
  }
  return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dkargin_grabbure_NativeBridge_nodeStarted(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lock(g_nodeMu);
  return miniros::isInitialized() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_nodeMaster(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_nodeMu);
  return grabbure::toJava(env, g_masterUri);
}

namespace grabbure {

bool copyNode(miniros::NodeHandle& node, std::string& error) {
  std::lock_guard<std::mutex> lock(g_nodeMu);
  if (!g_node) {
    error = "Node is not started";
    return false;
  }
  node = *g_node;
  return true;
}

}  // namespace grabbure

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_startPublisher(JNIEnv* env, jclass, jboolean jpeg) {
  miniros::NodeHandle node;
  {
    std::lock_guard<std::mutex> lock(g_nodeMu);
    if (!g_node) {
      return grabbure::toJava(env, "Node is not started");
    }
    node = *g_node;
  }
  g_camera.start(node, jpeg == JNI_TRUE);
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dkargin_grabbure_NativeBridge_stopPublisher(JNIEnv*, jclass) {
  g_camera.stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_currentTopic(JNIEnv* env, jclass) {
  return grabbure::toJava(env, g_camera.topic());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_dkargin_grabbure_NativeBridge_publishedTopics(JNIEnv* env, jclass) {
  std::vector<std::string> topics = g_camera.topics();
  const std::vector<std::string> motion = grabbure::motionTopics();
  topics.insert(topics.end(), motion.begin(), motion.end());
  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(topics.size()), stringClass, nullptr);
  if (!array) {
    return nullptr;
  }
  for (jsize i = 0; i < static_cast<jsize>(topics.size()); ++i) {
    env->SetObjectArrayElement(array, i, grabbure::toJava(env, topics[static_cast<size_t>(i)]));
  }
  return array;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dkargin_grabbure_NativeBridge_publishJpeg(JNIEnv* env, jclass, jbyteArray data) {
  if (!data) {
    return JNI_FALSE;
  }
  const jsize size = env->GetArrayLength(data);
  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  if (!bytes) {
    return JNI_FALSE;
  }
  sensor_msgs::CompressedImage msg;
  msg.format = "jpeg";
  msg.data.assign(reinterpret_cast<uint8_t*>(bytes), reinterpret_cast<uint8_t*>(bytes) + size);
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  return g_camera.publishCompressed(msg) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dkargin_grabbure_NativeBridge_publishYuv(JNIEnv* env, jclass, jobject yBuf, jobject uBuf,
                                                   jobject vBuf, jint width, jint height, jint yRow,
                                                   jint uRow, jint vRow, jint yPix, jint uPix, jint vPix) {
  int yAvail = 0;
  int uAvail = 0;
  int vAvail = 0;
  const uint8_t* y = bufferData(env, yBuf, &yAvail);
  const uint8_t* u = bufferData(env, uBuf, &uAvail);
  const uint8_t* v = bufferData(env, vBuf, &vAvail);
  if (!y || !u || !v || width < 2 || height < 2) {
    return JNI_FALSE;
  }

  sensor_msgs::Image msg;
  msg.width = static_cast<uint32_t>(width);
  msg.height = static_cast<uint32_t>(height);
  msg.encoding = "yuv420";
  msg.is_bigendian = 0;
  msg.step = static_cast<uint32_t>(width);
  const int chromaWidth = width / 2;
  const int chromaHeight = height / 2;
  if (!appendPlane(msg.data, y, yAvail, yRow, yPix, width, height) ||
      !appendPlane(msg.data, u, uAvail, uRow, uPix, chromaWidth, chromaHeight) ||
      !appendPlane(msg.data, v, vAvail, vRow, vPix, chromaWidth, chromaHeight)) {
    return JNI_FALSE;
  }
  return g_camera.publishRaw(msg) ? JNI_TRUE : JNI_FALSE;
}
