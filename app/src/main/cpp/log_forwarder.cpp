#include "log_forwarder.h"

#include "jni_util.h"

#include <jni.h>

#include <cstdio>
#include <ctime>
#include <deque>
#include <mutex>

#include <miniros/console.h>

namespace grabbure {
namespace {

std::mutex g_logMu;
std::deque<std::string> g_log;
bool g_logReady = false;

const char* kVerboseLoggers[] = {
    "miniros.unknown_package.discovery",
    "miniros.unknown_package",
    "miniros.net.socket",
    "miniros.net",
};

std::string stamp() {
  const std::time_t now = std::time(nullptr);
  std::tm local{};
  localtime_r(&now, &local);
  char buf[16];
  std::snprintf(buf, sizeof(buf), "%02d:%02d:%02d", local.tm_hour, local.tm_min, local.tm_sec);
  return buf;
}

const char* levelName(miniros::console::Level level) {
  switch (level) {
    case miniros::console::Level::Debug:
      return "D";
    case miniros::console::Level::Info:
      return "I";
    case miniros::console::Level::Warn:
      return "W";
    case miniros::console::Level::Error:
      return "E";
    case miniros::console::Level::Fatal:
      return "F";
    default:
      return "?";
  }
}

class AppLogAppender : public miniros::console::LogAppender {
 public:
  void log(miniros::console::Level level, const char* str, const char*, const char*, int) override {
    pushAppLog(stamp() + " " + levelName(level) + " " + (str ? str : ""));
  }
};

AppLogAppender g_appender;

}  // namespace

void ensureAppLog() {
  if (g_logReady) {
    return;
  }
  miniros::console::initialize();
  miniros::console::register_appender(&g_appender);
  g_logReady = true;
}

void setVerboseLogging(bool verbose) {
  ensureAppLog();
  const auto level = verbose ? miniros::console::Level::Debug : miniros::console::Level::Info;
  for (const char* name : kVerboseLoggers) {
    miniros::console::set_logger_level(name, level);
  }
  miniros::console::notifyLoggerLevelsChanged();
  pushAppLog(stamp() + " I log level " + (verbose ? "debug" : "info"));
}

void pushAppLog(const std::string& line) {
  std::lock_guard<std::mutex> lock(g_logMu);
  g_log.push_back(line);
  while (g_log.size() > 200) {
    g_log.pop_front();
  }
}

std::string drainAppLog() {
  std::string out;
  std::lock_guard<std::mutex> lock(g_logMu);
  for (const std::string& line : g_log) {
    out += line;
    out += '\n';
  }
  g_log.clear();
  return out;
}

}  // namespace grabbure

extern "C" JNIEXPORT void JNICALL
Java_com_dkargin_grabbure_NativeBridge_setVerbose(JNIEnv*, jclass, jboolean verbose) {
  grabbure::setVerboseLogging(verbose == JNI_TRUE);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dkargin_grabbure_NativeBridge_drainLog(JNIEnv* env, jclass) {
  return grabbure::toJava(env, grabbure::drainAppLog());
}
