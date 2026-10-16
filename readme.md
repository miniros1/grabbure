# Grabbure

Android camera publisher for miniros. The first screen lists nearby masters from UDP discovery. The next screen chooses a camera, JPEG or YUV 420, and a resolution, then publishes `camera/image_raw/compressed` or `camera/image_raw`.

Open this directory in Android Studio (`/media/dkargin/dkargin-bags/android-studio`). `local.properties` points at the SDK and at `/usr/bin/cmake`. The native library links `~/install-android/<abi>` and is built for `arm64-v8a` only.

The node is initialized once per process. Restart the app to publish to a different master. The phone must be on the same Wi-Fi network, and its IPv4 address is sent as `ROS_IP`.
