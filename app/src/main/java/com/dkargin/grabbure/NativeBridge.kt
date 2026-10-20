package com.dkargin.grabbure

object NativeBridge {
    init {
        System.loadLibrary("grabbure")
    }

    @JvmStatic external fun setVerbose(verbose: Boolean)

    @JvmStatic external fun drainLog(): String

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

    @JvmStatic external fun startPublisher(cameraId: String, jpeg: Boolean): String?

    @JvmStatic external fun stopPublisher()

    @JvmStatic external fun currentTopic(): String

    @JvmStatic external fun publishedTopics(): Array<String>

    @JvmStatic external fun publishJpeg(data: ByteArray): Boolean

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
    ): Boolean

    @JvmStatic external fun startImu(temperature: Boolean): String?

    @JvmStatic external fun stopImu()

    @JvmStatic external fun imuTopic(): String

    @JvmStatic external fun temperatureTopic(): String

    /** Degrees Celsius. */
    @JvmStatic external fun publishTemperature(celsius: Double): Boolean

    /** Android device frame, rad/s. */
    @JvmStatic external fun publishGyro(x: Double, y: Double, z: Double): Boolean

    /** Android device frame, m/s^2, including gravity. */
    @JvmStatic external fun publishAccel(x: Double, y: Double, z: Double): Boolean

    @JvmStatic external fun startMagnetometer(): String?

    @JvmStatic external fun stopMagnetometer()

    @JvmStatic external fun magnetometerTopic(): String

    /** Android device frame, microtesla. */
    @JvmStatic external fun publishMagneticField(x: Double, y: Double, z: Double): Boolean

    @JvmStatic external fun startGps(): String?

    @JvmStatic external fun stopGps()

    @JvmStatic external fun gpsTopic(): String

    /**
     * WGS84 degrees and ellipsoid meters.
     * A negative accuracy means that axis is unknown.
     */
    @JvmStatic external fun publishNavSat(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        hasAltitude: Boolean,
        horizontalAccuracyM: Double,
        verticalAccuracyM: Double,
    ): Boolean
}
