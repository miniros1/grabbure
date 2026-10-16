package com.dkargin.grabbure

object NativeBridge {
    init {
        System.loadLibrary("grabbure")
    }

    @JvmStatic external fun discoveryStart(): String?

    @JvmStatic external fun discoveryStop()

    @JvmStatic external fun discoveryPoll(): Array<DiscoveredMaster>

    @JvmStatic external fun nodeStart(masterUri: String, ip: String, filesDir: String): String?

    @JvmStatic external fun nodeStarted(): Boolean

    @JvmStatic external fun nodeMaster(): String

    @JvmStatic external fun startPublisher(jpeg: Boolean): String?

    @JvmStatic external fun stopPublisher()

    @JvmStatic external fun currentTopic(): String

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
}
