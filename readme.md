# Grabbure

Android sensor publisher for miniros. The first screen lists nearby masters from UDP discovery. Choosing a master starts the camera, IMU, and magnetometer. Camera publishes `android_camera/image_raw/compressed` or `android_camera/image_raw` with the last saved settings. IMU publishes `android_imu/data` (`sensor_msgs/Imu`, gyroscope and accelerometer). Magnetometer publishes `android_magnetometer/data` (`sensor_msgs/MagneticField`).

Open this directory in Android Studio (`/media/dkargin/dkargin-bags/android-studio`). `local.properties` points at the SDK and at `/usr/bin/cmake`. The native library links `~/install-android/<abi>` and is built for `arm64-v8a` only.

The node is initialized once per process. Restart the app to publish to a different master. The phone must be on the same Wi-Fi network, and its IPv4 address is sent as `ROS_IP`.
