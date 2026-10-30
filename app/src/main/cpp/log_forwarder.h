#pragma once

namespace grabbure {

/// Start the miniros console so Android builds write to logcat.
void ensureAppLog();

/// Debug shows discovery and socket traces. Info keeps warnings and errors.
void setVerboseLogging(bool verbose);

}  // namespace grabbure
