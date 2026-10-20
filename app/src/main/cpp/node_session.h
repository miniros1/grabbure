#pragma once

#include <string>

#include <miniros/ros.h>

namespace grabbure {

/// Copy of the process node. False when `nodeStart` has not succeeded.
bool copyNode(miniros::NodeHandle& node, std::string& error);

/// Frame ids cannot contain '/'. `/android/imu` becomes `android_imu`.
inline std::string frameId(const miniros::NodeHandle& node) {
  std::string id;
  for (char c : node.getNamespace()) {
    if (c == '/') {
      if (!id.empty() && id.back() != '_') {
        id.push_back('_');
      }
    } else {
      id.push_back(c);
    }
  }
  return id;
}

}  // namespace grabbure
