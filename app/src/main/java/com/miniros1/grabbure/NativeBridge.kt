package com.miniros1.grabbure

object NativeBridge {
    init {
        System.loadLibrary("grabbure")
    }

    @JvmStatic external fun setVerbose(verbose: Boolean)

    @JvmStatic external fun discoveryStart(): String?

    @JvmStatic external fun discoveryStop()

    @JvmStatic external fun discoveryPoll(): Array<DiscoveredMaster>

    @JvmStatic external fun nodeStart(masterUri: String, ip: String, phone: String): String?

    /** Start an in-process master. Null on success; the URI is then `localMasterUri()`. */
    @JvmStatic external fun startLocalMaster(ip: String, phone: String): String?

    @JvmStatic external fun localMasterUri(): String

    /** One pass of `master::Master::update`. Discovery repeats only while this is called. */
    @JvmStatic external fun masterUpdate()

    @JvmStatic external fun nodeStarted(): Boolean

    @JvmStatic external fun nodeMaster(): String

    @JvmStatic external fun nodeName(): String

    /**
     * Open a rosbag and record into it. No master and no node.
     * `cameraId` empty skips images. Images are JPEG only.
     */
    @JvmStatic external fun startBag(
        path: String,
        phone: String,
        cameraId: String,
        imu: Boolean,
        temperature: Boolean,
        magnetometer: Boolean,
        gps: Boolean,
    ): String?

    @JvmStatic external fun stopBag()

    @JvmStatic external fun startPublisher(cameraId: String, jpeg: Boolean): String?

    @JvmStatic external fun stopPublisher()

    @JvmStatic external fun currentTopic(): String

    @JvmStatic external fun publishedTopics(): Array<String>

    /** `timestampNs` is `Image.timestamp`, the exposure start. */
    @JvmStatic external fun publishJpeg(data: ByteArray, timestampNs: Long): Boolean

    /** `timestampNs` is `Image.timestamp`, the exposure start. */
    @JvmStatic external fun publishYuv(
        y: java.nio.ByteBuffer,
        u: java.nio.ByteBuffer,
        v: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        yRow: Int,
        uRow: Int,
        vRow: Int,
        yPix: Int,
        uPix: Int,
        vPix: Int,
        timestampNs: Long,
    ): Boolean

    @JvmStatic external fun startImu(temperature: Boolean): String?

    @JvmStatic external fun stopImu()

    @JvmStatic external fun imuTopic(): String

    @JvmStatic external fun temperatureTopic(): String

    /** Degrees Celsius. `timestampNs` is `SensorEvent.timestamp`. */
    @JvmStatic external fun publishTemperature(celsius: Double, timestampNs: Long): Boolean

    /** Android device frame, rad/s. `timestampNs` is `SensorEvent.timestamp`. */
    @JvmStatic external fun publishGyro(x: Double, y: Double, z: Double, timestampNs: Long): Boolean

    /** Android device frame, m/s^2, including gravity. `timestampNs` is `SensorEvent.timestamp`. */
    @JvmStatic external fun publishAccel(x: Double, y: Double, z: Double, timestampNs: Long): Boolean

    @JvmStatic external fun startMagnetometer(): String?

    @JvmStatic external fun stopMagnetometer()

    @JvmStatic external fun magnetometerTopic(): String

    /** Android device frame, microtesla. `timestampNs` is `SensorEvent.timestamp`. */
    @JvmStatic external fun publishMagneticField(x: Double, y: Double, z: Double, timestampNs: Long): Boolean

    @JvmStatic external fun startGps(): String?

    @JvmStatic external fun stopGps()

    @JvmStatic external fun gpsTopic(): String

    /**
     * WGS84 degrees and ellipsoid meters.
     * A negative accuracy means that axis is unknown.
     * `timestampNs` is `Location.elapsedRealtimeNanos`.
     */
    @JvmStatic external fun publishNavSat(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        hasAltitude: Boolean,
        horizontalAccuracyM: Double,
        verticalAccuracyM: Double,
        timestampNs: Long,
    ): Boolean
}
