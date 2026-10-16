#pragma once

#include <string>

namespace grabbure {

/// Install the miniros console appender that copies lines into the app log.
void ensureAppLog();

/// Debug shows discovery and socket traces. Info keeps warnings and errors.
void setVerboseLogging(bool verbose);

void pushAppLog(const std::string& line);

/// Lines accumulated since the previous call.
std::string drainAppLog();

}  // namespace grabbure
