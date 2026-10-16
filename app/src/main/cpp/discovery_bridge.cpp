#include "jni_util.h"
#include "log_forwarder.h"

#include <jni.h>

#include <memory>
#include <mutex>
#include <vector>

#include <miniros/master_discovery.h>

namespace {

std::mutex g_discoveryMu;
std::unique_ptr<miniros::MasterDiscovery> g_discovery;

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_discoveryStart(JNIEnv* env, jclass) {
  grabbure::ensureAppLog();
  std::lock_guard<std::mutex> lock(g_discoveryMu);
  if (!g_discovery) {
    g_discovery.reset(new miniros::MasterDiscovery());
  }
  if (g_discovery->started()) {
    return nullptr;
  }
  const miniros::Error error = g_discovery->start();
  if (!error) {
    return grabbure::toJava(env, std::string("Discovery failed: ") + error.toString());
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
        grabbure::toJava(env, master.uuid.toString()),
        grabbure::toJava(env, master.hostname),
        grabbure::toJava(env, master.address),
        static_cast<jint>(master.masterPort),
        grabbure::toJava(env, master.uri),
        static_cast<jboolean>(master.hasToken));
    env->SetObjectArrayElement(array, static_cast<jsize>(i), obj);
    env->DeleteLocalRef(obj);
  }
  return array;
}
