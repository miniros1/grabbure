#include <jni.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <vector>

#include <miniros/datatypes.h>
#include <miniros/master_discovery.h>
#include <miniros/ros.h>
#include <sensor_msgs/CompressedImage.hxx>
#include <sensor_msgs/Image.hxx>

namespace {

std::mutex g_discoveryMu;
std::unique_ptr<miniros::MasterDiscovery> g_discovery;

std::mutex g_nodeMu;
std::unique_ptr<miniros::NodeHandle> g_node;
std::unique_ptr<miniros::AsyncSpinner> g_spinner;
miniros::Publisher g_jpegPub;
miniros::Publisher g_yuvPub;
std::string g_masterUri;
std::string g_topic;
bool g_jpeg = false;
std::atomic<bool> g_busy{false};

std::string fromJava(JNIEnv* env, jstring value) {
  if (!value) {
    return {};
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string out = chars ? chars : "";
  if (chars) {
    env->ReleaseStringUTFChars(value, chars);
  }
  return out;
}

jstring toJava(JNIEnv* env, const std::string& value) {
  return env->NewStringUTF(value.c_str());
}

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

struct BusyGuard {
  std::atomic<bool>& flag;
  bool held;
  explicit BusyGuard(std::atomic<bool>& flagIn) : flag(flagIn), held(false) {
    bool expected = false;
    held = flag.compare_exchange_strong(expected, true);
  }
  ~BusyGuard() {
    if (held) {
      flag.store(false);
    }
  }
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_discoveryStart(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_discoveryMu);
  if (!g_discovery) {
    g_discovery.reset(new miniros::MasterDiscovery());
  }
  if (g_discovery->started()) {
    return nullptr;
  }
  const miniros::Error error = g_discovery->start();
  if (!error) {
    return toJava(env, std::string("Discovery failed: ") + error.toString());
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dkargin_grabbure_NativeBridge_discoveryStop(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lock(g_discoveryMu);
  if (g_discovery) {
    g_discovery->stop();
  }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_dkargin_grabbure_NativeBridge_discoveryPoll(JNIEnv* env, jclass) {
  jclass cls = env->FindClass("com/dkargin/grabbure/DiscoveredMaster");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Z)V");

  std::vector<miniros::DiscoveredMaster> masters;
  {
    std::lock_guard<std::mutex> lock(g_discoveryMu);
    if (g_discovery && g_discovery->started()) {
      g_discovery->update();
      masters = g_discovery->masters();
    }
  }

  jobjectArray array = env->NewObjectArray(static_cast<jsize>(masters.size()), cls, nullptr);
  for (size_t i = 0; i < masters.size(); ++i) {
    const miniros::DiscoveredMaster& master = masters[i];
    jobject obj = env->NewObject(
        cls, ctor,
        toJava(env, master.uuid.toString()),
        toJava(env, master.hostname),
        toJava(env, master.address),
        static_cast<jint>(master.masterPort),
        toJava(env, master.uri),
        static_cast<jboolean>(master.hasToken));
    env->SetObjectArrayElement(array, static_cast<jsize>(i), obj);
    env->DeleteLocalRef(obj);
  }
  return array;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_nodeStart(JNIEnv* env, jclass, jstring masterUri, jstring ip,
                                                  jstring filesDir) {
  const std::string uri = fromJava(env, masterUri);
  const std::string address = fromJava(env, ip);
  const std::string files = fromJava(env, filesDir);
  if (uri.empty() || address.empty() || files.empty()) {
    return toJava(env, "Master URI, phone address, and files directory are required");
  }

  std::lock_guard<std::mutex> lock(g_nodeMu);
  if (miniros::isInitialized()) {
    if (g_masterUri != uri) {
      return toJava(env, "This process is already connected to " + g_masterUri +
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
    g_spinner.reset(new miniros::AsyncSpinner(1));
    g_spinner->start();
    g_masterUri = uri;
  } catch (const std::exception& ex) {
    return toJava(env, ex.what());
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
  return toJava(env, g_masterUri);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_startPublisher(JNIEnv* env, jclass, jboolean jpeg) {
  std::lock_guard<std::mutex> lock(g_nodeMu);
  if (!g_node) {
    return toJava(env, "Node is not started");
  }
  g_jpeg = jpeg == JNI_TRUE;
  if (g_jpeg) {
    g_yuvPub = miniros::Publisher();
    g_jpegPub = g_node->advertise<sensor_msgs::CompressedImage>("camera/image_raw/compressed", 1);
    g_topic = "camera/image_raw/compressed";
  } else {
    g_jpegPub = miniros::Publisher();
    g_yuvPub = g_node->advertise<sensor_msgs::Image>("camera/image_raw", 1);
    g_topic = "camera/image_raw";
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dkargin_grabbure_NativeBridge_stopPublisher(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lock(g_nodeMu);
  g_jpegPub = miniros::Publisher();
  g_yuvPub = miniros::Publisher();
  g_topic.clear();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_currentTopic(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_nodeMu);
  return toJava(env, g_topic);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dkargin_grabbure_NativeBridge_publishJpeg(JNIEnv* env, jclass, jbyteArray data) {
  if (!data) {
    return JNI_FALSE;
  }
  BusyGuard busy(g_busy);
  if (!busy.held) {
    return JNI_FALSE;
  }
  std::lock_guard<std::mutex> lock(g_nodeMu);
  if (!g_jpegPub) {
    return JNI_FALSE;
  }
  const jsize size = env->GetArrayLength(data);
  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  if (!bytes) {
    return JNI_FALSE;
  }
  sensor_msgs::CompressedImage msg;
  msg.header.stamp = miniros::Time::now();
  msg.header.frame_id = "camera";
  msg.format = "jpeg";
  msg.data.assign(reinterpret_cast<uint8_t*>(bytes), reinterpret_cast<uint8_t*>(bytes) + size);
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  g_jpegPub.publish(msg);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dkargin_grabbure_NativeBridge_publishYuv(JNIEnv* env, jclass, jobject yBuf, jobject uBuf,
                                                   jobject vBuf, jint width, jint height, jint yRow,
                                                   jint uRow, jint vRow, jint yPix, jint uPix, jint vPix) {
  BusyGuard busy(g_busy);
  if (!busy.held) {
    return JNI_FALSE;
  }
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
  msg.header.stamp = miniros::Time::now();
  msg.header.frame_id = "camera";

  std::lock_guard<std::mutex> lock(g_nodeMu);
  if (!g_yuvPub) {
    return JNI_FALSE;
  }
  g_yuvPub.publish(msg);
  return JNI_TRUE;
}
