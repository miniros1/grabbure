# Grabbure

Android sensor publisher for miniros. The first screen lists nearby masters from UDP discovery and a phone name (default `android`). Choosing a master, or starting a master on this phone, starts the camera, IMU, and magnetometer under that name, so several phones can share one master. GPS is published when the sensor screen has that option on. Choosing a different master or phone name after the node has started restarts the app and connects to that choice.

Topics, with `N` the camera id:

- `<phone>/cameraN/image_raw` or `<phone>/cameraN/image_raw/compressed`
- `<phone>/imu/raw` (`sensor_msgs/Imu`, gyroscope and accelerometer)
- `<phone>/imu/temp` (`sensor_msgs/Temperature`) when the phone exposes a temperature sensor
- `<phone>/mag/raw` (`sensor_msgs/MagneticField`)
- `<phone>/gps` (`sensor_msgs/NavSatFix`) when Publish GPS is on, the phone has a GPS provider, and location permission is granted

`<phone>/imu/filtered` and `<phone>/mag/filtered` are the names reserved for filtered streams. They are advertised when a filter is publishing.

Open this directory in Android Studio (`/media/dkargin/dkargin-bags/android-studio`). `local.properties` points at the SDK and at `/usr/bin/cmake`. The native library links `~/install-android/<abi>` and is built for `arm64-v8a` only.

The node is initialized once per process, using the phone name. A different master or phone name is saved, then the process is restarted and that connection starts on the next launch. The phone must be on the same Wi-Fi network. Its IPv4 address is passed as the `__ip` remap. A local master is an in-process `master::Master` on port 11311. The app calls `update()` about ten times a second so discovery keeps going. Rosout is not started.
