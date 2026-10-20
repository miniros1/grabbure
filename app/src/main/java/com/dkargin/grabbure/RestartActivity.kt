package com.dkargin.grabbure

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kotlin.system.exitProcess

/**
 * Starts the master screen in a new main process, then exits this helper process.
 * The main process has already saved the next master and is exiting.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MasterActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
        exitProcess(0)
    }
}
