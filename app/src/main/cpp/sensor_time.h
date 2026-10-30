#pragma once

#include <miniros/rostime.h>

#include <cstdint>
#include <ctime>

namespace grabbure {

/// Map an Android `CLOCK_BOOTTIME` timestamp onto wall time.
/// `SensorEvent.timestamp`, `Image.getTimestamp`, and `Location.getElapsedRealtimeNanos`
/// share this clock. The offset is measured at the call, so a late delivery does not move the stamp.
inline miniros::Time timeFromBootNanos(int64_t bootNs) {
  if (bootNs <= 0) {
    return miniros::Time::now();
  }
  timespec real{};
  timespec boot{};
  if (clock_gettime(CLOCK_REALTIME, &real) != 0 || clock_gettime(CLOCK_BOOTTIME, &boot) != 0) {
    return miniros::Time::now();
  }
  const int64_t realNs = static_cast<int64_t>(real.tv_sec) * 1000000000LL + static_cast<int64_t>(real.tv_nsec);
  const int64_t bootNow = static_cast<int64_t>(boot.tv_sec) * 1000000000LL + static_cast<int64_t>(boot.tv_nsec);
  int64_t stampNs = bootNs + (realNs - bootNow);
  if (stampNs < 0) {
    stampNs = 0;
  }
  return miniros::Time(static_cast<uint32_t>(stampNs / 1000000000LL),
                       static_cast<uint32_t>(stampNs % 1000000000LL));
}

}  // namespace grabbure
