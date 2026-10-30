#include "bag_writer.h"
#include "camera_publisher.h"
#include "jni_util.h"
#include "motion_topics.h"
#include "node_session.h"

#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <miniros/datatypes.h>
#include <miniros/roscore/master.h>
#include <miniros/ros.h>
#include <miniros/transport/rpc_manager.h>

namespace {

std::mutex g_nodeMu;
std::unique_ptr<miniros::NodeHandle> g_node;
std::string g_masterUri;
std::string g_nodeName;
std::unique_ptr<miniros::master::Master> g_master;
std::string g_localUri;
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
Java_com_miniros1_grabbure_NativeBridge_nodeStart(JNIEnv* env, jclass, jstring masterUri, jstring ip,
                                                  jstring phone) {
  const std::string uri = grabbure::fromJava(env, masterUri);
  const std::string address = grabbure::fromJava(env, ip);
  std::string nodeName = grabbure::fromJava(env, phone);
  if (nodeName.empty()) {
    nodeName = "android";
  }
  if (uri.empty() || address.empty()) {
    return grabbure::toJava(env, "Master URI and phone address are required");
  }

  std::lock_guard<std::mutex> lock(g_nodeMu);
  if (miniros::isInitialized()) {
    if (g_masterUri != uri) {
      return grabbure::toJava(env, "This process is already connected to " + g_masterUri +
                                       ". Restart the app to use another master.");
    }
    if (g_nodeName != nodeName) {
      return grabbure::toJava(env, "This process is already named " + g_nodeName +
                                       ". Restart the app to use another phone name.");
    }
    return nullptr;
  }

  miniros::M_string remaps;
  remaps["__master"] = uri;
  remaps["__ip"] = address;
  try {
    miniros::init(remaps, nodeName,
                  miniros::init_options::NoSigintHandler | miniros::init_options::NoRosout |
                      miniros::init_options::NoFileLog);
    g_node.reset(new miniros::NodeHandle(nodeName));
    g_masterUri = uri;
    g_nodeName = nodeName;
  } catch (const std::exception& ex) {
    return grabbure::toJava(env, ex.what());
  }
  return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_startLocalMaster(JNIEnv* env, jclass, jstring ip, jstring phone) {
  const std::string address = grabbure::fromJava(env, ip);
  std::string nodeName = grabbure::fromJava(env, phone);
  if (nodeName.empty()) {
    nodeName = "android";
  }
  if (address.empty()) {
    return grabbure::toJava(env, "Phone address is required");
  }

  std::lock_guard<std::mutex> lock(g_nodeMu);
  if (miniros::isInitialized()) {
    if (g_localUri.empty()) {
      return grabbure::toJava(env, "This process is already connected to " + g_masterUri +
                                       ". Restart the app to start a local master.");
    }
    if (g_nodeName != nodeName) {
      return grabbure::toJava(env, "This process is already named " + g_nodeName +
                                       ". Restart the app to use another phone name.");
    }
    return nullptr;
  }

  constexpr int kPort = 11311;
  auto rpc = miniros::RPCManager::instance();
  g_master.reset(new miniros::master::Master(rpc));
  g_master->setCacheEnabled(false);
  g_master->setMultimasterEnabled(true);
  g_master->setNodeCheckPeriod(5.0);

  miniros::M_string remaps;
  remaps["__rpc_server_port"] = std::to_string(kPort);
  remaps["__local_master"] = "1";
  remaps["__ip"] = address;
  try {
    miniros::init(remaps, nodeName,
                  miniros::init_options::NoSigintHandler | miniros::init_options::NoRosout |
                      miniros::init_options::NoFileLog);
    if (miniros::Error err = miniros::start(); !err) {
      return grabbure::toJava(env, std::string("Local master failed to start: ") + err.toString());
    }
    miniros::PollSet* pollSet = rpc->getPollSet();
    if (!pollSet || !g_master->start(pollSet, kPort)) {
      return grabbure::toJava(env, "Local master failed to bind");
    }
    g_master->registerSelfRef();
    g_node.reset(new miniros::NodeHandle(nodeName));
  } catch (const std::exception& ex) {
    return grabbure::toJava(env, ex.what());
  }
  g_localUri = g_master->getUri();
  if (g_localUri.empty()) {
    return grabbure::toJava(env, "Local master started without a URI");
  }
  g_masterUri = g_localUri;
  g_nodeName = nodeName;
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_miniros1_grabbure_NativeBridge_masterUpdate(JNIEnv*, jclass) {
  miniros::master::Master* master = nullptr;
  {
    std::lock_guard<std::mutex> lock(g_nodeMu);
    master = g_master.get();
  }
  if (master) {
    master->update();
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_localMasterUri(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_nodeMu);
  return grabbure::toJava(env, g_localUri);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_miniros1_grabbure_NativeBridge_nodeStarted(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lock(g_nodeMu);
  return miniros::isInitialized() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_nodeMaster(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_nodeMu);
  return grabbure::toJava(env, g_masterUri);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_nodeName(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_nodeMu);
  return grabbure::toJava(env, g_nodeName);
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
Java_com_miniros1_grabbure_NativeBridge_startPublisher(JNIEnv* env, jclass, jstring cameraId,
                                                       jboolean jpeg) {
  miniros::NodeHandle node;
  {
    std::lock_guard<std::mutex> lock(g_nodeMu);
    if (!g_node) {
      return grabbure::toJava(env, "Node is not started");
    }
    node = *g_node;
  }
  try {
    g_camera.start(node, grabbure::fromJava(env, cameraId), jpeg == JNI_TRUE);
  } catch (const std::exception& ex) {
    return grabbure::toJava(env, ex.what());
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_miniros1_grabbure_NativeBridge_stopPublisher(JNIEnv*, jclass) {
  g_camera.stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_startBag(JNIEnv* env, jclass, jstring path, jstring phone,
                                                 jstring cameraId, jboolean imu, jboolean temperature,
                                                 jboolean magnetometer, jboolean gps) {
  const std::string error = grabbure::bagWriter().open(
      grabbure::fromJava(env, path), grabbure::fromJava(env, phone), grabbure::fromJava(env, cameraId),
      imu == JNI_TRUE, temperature == JNI_TRUE, magnetometer == JNI_TRUE, gps == JNI_TRUE);
  if (error.empty()) {
    return nullptr;
  }
  return grabbure::toJava(env, error);
}

extern "C" JNIEXPORT void JNICALL
Java_com_miniros1_grabbure_NativeBridge_stopBag(JNIEnv*, jclass) {
  grabbure::bagWriter().close();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_miniros1_grabbure_NativeBridge_currentTopic(JNIEnv* env, jclass) {
  const std::string bagTopic = grabbure::bagWriter().cameraTopic();
  if (!bagTopic.empty()) {
    return grabbure::toJava(env, bagTopic);
  }
  return grabbure::toJava(env, g_camera.topic());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_miniros1_grabbure_NativeBridge_publishedTopics(JNIEnv* env, jclass) {
  std::vector<std::string> topics = grabbure::bagWriter().topics();
  if (topics.empty()) {
    topics = g_camera.topics();
    const std::vector<std::string> motion = grabbure::motionTopics();
    topics.insert(topics.end(), motion.begin(), motion.end());
  }
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
Java_com_miniros1_grabbure_NativeBridge_publishJpeg(JNIEnv* env, jclass, jbyteArray data, jlong timestampNs) {
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
  if (grabbure::bagWriter().isOpen()) {
    return grabbure::bagWriter().writeJpeg(msg.data.data(), static_cast<int>(msg.data.size()), timestampNs)
               ? JNI_TRUE
               : JNI_FALSE;
  }
  return g_camera.publishCompressed(msg, timestampNs) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_miniros1_grabbure_NativeBridge_publishYuv(JNIEnv* env, jclass, jobject yBuf, jobject uBuf,
                                                   jobject vBuf, jint width, jint height, jint yRow,
                                                   jint uRow, jint vRow, jint yPix, jint uPix, jint vPix,
                                                   jlong timestampNs) {
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
  return g_camera.publishRaw(msg, timestampNs) ? JNI_TRUE : JNI_FALSE;
}
