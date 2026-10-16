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

    @JvmStatic external fun nodeStart(masterUri: String, ip: String, filesDir: String): String?

    @JvmStatic external fun nodeStarted(): Boolean

    @JvmStatic external fun nodeMaster(): String

    @JvmStatic external fun startPublisher(jpeg: Boolean): String?

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

    @JvmStatic external fun startImu(): String?

    @JvmStatic external fun stopImu()

    @JvmStatic external fun imuTopic(): String

    /** Android device frame, rad/s. */
    @JvmStatic external fun publishGyro(x: Double, y: Double, z: Double): Boolean

    /** Android device frame, m/s^2, including gravity. */
    @JvmStatic external fun publishAccel(x: Double, y: Double, z: Double): Boolean

    @JvmStatic external fun startMagnetometer(): String?

    @JvmStatic external fun stopMagnetometer()

    @JvmStatic external fun magnetometerTopic(): String

    /** Android device frame, microtesla. */
    @JvmStatic external fun publishMagneticField(x: Double, y: Double, z: Double): Boolean
}
