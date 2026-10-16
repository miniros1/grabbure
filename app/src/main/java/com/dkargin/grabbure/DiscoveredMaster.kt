package com.dkargin.grabbure

data class DiscoveredMaster(
    val uuid: String,
    val hostname: String,
    val address: String,
    val masterPort: Int,
    val uri: String,
    val hasToken: Boolean,
) {
    fun label(): String {
        val name = hostname.ifBlank { address.ifBlank { uuid } }
        return if (masterPort > 0) "$name:$masterPort" else name
    }
}
