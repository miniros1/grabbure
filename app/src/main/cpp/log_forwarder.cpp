#include "log_forwarder.h"

#include <jni.h>

#include <miniros/console.h>

namespace grabbure {
namespace {

const char* kVerboseLoggers[] = {
    "miniros.unknown_package.discovery",
    "miniros.unknown_package",
    "miniros.net.socket",
    "miniros.net",
};

}  // namespace

void ensureAppLog() {
  miniros::console::initialize();
}

void setVerboseLogging(bool verbose) {
  ensureAppLog();
  const auto level = verbose ? miniros::console::Level::Debug : miniros::console::Level::Info;
  for (const char* name : kVerboseLoggers) {
    miniros::console::set_logger_level(name, level);
  }
  miniros::console::notifyLoggerLevelsChanged();
}

}  // namespace grabbure

extern "C" JNIEXPORT void JNICALL
Java_com_miniros1_grabbure_NativeBridge_setVerbose(JNIEnv*, jclass, jboolean verbose) {
  grabbure::setVerboseLogging(verbose == JNI_TRUE);
}
