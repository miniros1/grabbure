#pragma once

#include <jni.h>

#include <string>

namespace grabbure {

inline std::string fromJava(JNIEnv* env, jstring value) {
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

inline jstring toJava(JNIEnv* env, const std::string& value) {
  return env->NewStringUTF(value.c_str());
}

}  // namespace grabbure
