# Grabbure

**Grabbure** grabs sensors of android-based smartphone into ROS1 topics. It captures camera, imu, magnetometer and (optionally) GPS data and publishes it into ROS1 topics.
It uses [miniros](https://github.com/dkargin/miniroscpp) as a client.

Grabbure publishes following topics:

- `<phone_name>/cameraN/image_raw` or `<phone>/cameraN/image_raw/compressed`
- `<phone_name>/imu/raw` (`sensor_msgs/Imu`, gyroscope and accelerometer)
- `<phone_name>/imu/temp` (`sensor_msgs/Temperature`) when the phone exposes a temperature sensor
- `<phone_name>/mag/raw` (`sensor_msgs/MagneticField`)
- `<phone_name>/gps` (`sensor_msgs/NavSatFix`) when Publish GPS is on and location permission is granted.

Grabbure can connect either to external ROS1/miniros master or host own master, so external clients can connect. It also provides an option to store all data streams directly to ROS BAG file. 

## Build

The app is built with Android Studio. Gradle compiles miniros from source once for each ABI it builds. With no `abiFilters`, that is the NDK default set: `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`. There is no separate miniros install step.

Create `local.properties` in this directory (it is not committed):

```
sdk.dir=/path/to/Android/SDK
cmake.dir=/usr
miniros.dir=/path/to/miniroscpp
```

`sdk.dir` is the Android SDK. `cmake.dir` is the directory that contains the `cmake` executable (CMake 3.22 or newer; 3.28 is what this project asks Gradle to use). `miniros.dir` is the miniros source tree, the directory whose `CMakeLists.txt` starts with `project(miniros ...)`.

The NDK version is `ndkVersion` in `app/build.gradle.kts` (`28.2.13676358`). Install that NDK from the SDK manager. The native build uses the NDK's `c++_static` runtime. Miniros is configured by that same Android toolchain: static `roscxx` and `bag_storage`, with bzip2 and lz4 from the miniros tree. `miniroscore` and `minibag` are not built as part of the app.

Open this directory in Android Studio and run the app configuration. Studio builds `:app:assembleDebug`. To build a shorter ABI list, set `ndk.abiFilters` in `defaultConfig` in `app/build.gradle.kts`. Miniros follows that list.

The node is initialized once per process, using the phone name. A different master or phone name is saved, then the process is restarted and that connection starts on the next launch. The phone must be on the same Wi-Fi network. Its IPv4 address is passed as the `__ip` remap. A local master is an in-process `master::Master` on port 11311. The app calls `update()` about ten times a second so discovery keeps going. Rosout is not started.
