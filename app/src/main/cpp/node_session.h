#pragma once

#include <string>

#include <miniros/ros.h>

namespace grabbure {

/// Copy of the process node. False when `nodeStart` has not succeeded.
bool copyNode(miniros::NodeHandle& node, std::string& error);

}  // namespace grabbure
