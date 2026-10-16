package com.dkargin.grabbure

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dkargin.grabbure.databinding.ActivityMasterBinding

class MasterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMasterBinding
    private lateinit var adapter: MasterAdapter
    private var multicastLock: WifiManager.MulticastLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMasterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adapter = MasterAdapter { master -> openCamera(master) }
        binding.masters.layoutManager = LinearLayoutManager(this)
        binding.masters.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi?.createMulticastLock("grabbure")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val error = try {
            NativeBridge.discoveryStart()
        } catch (ex: UnsatisfiedLinkError) {
            ex.message ?: "Native library failed to load"
        }
        val address = NetInfo.wifiIpv4(this)
        binding.phoneAddress.text = if (address == null) {
            getString(R.string.no_wifi_address)
        } else {
            getString(R.string.phone_address, address)
        }
        if (error != null) {
            binding.status.text = error
            return
        }
        handler.post(poll)
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        try {
            NativeBridge.discoveryStop()
        } catch (_: UnsatisfiedLinkError) {
        }
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        super.onStop()
    }

    private fun refresh() {
        val masters = NativeBridge.discoveryPoll().toList()
        adapter.submit(masters)
        binding.status.text = if (masters.isEmpty()) {
            getString(R.string.listening)
        } else {
            resources.getQuantityString(R.plurals.masters_found, masters.size, masters.size)
        }
    }

    private fun openCamera(master: DiscoveredMaster) {
        if (master.uri.isBlank()) {
            Toast.makeText(this, R.string.master_without_address, Toast.LENGTH_SHORT).show()
            return
        }
        val address = NetInfo.wifiIpv4(this)
        if (address == null) {
            Toast.makeText(this, R.string.no_wifi_address, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, CameraActivity::class.java)
                .putExtra(CameraActivity.EXTRA_URI, master.uri)
                .putExtra(CameraActivity.EXTRA_LABEL, master.label())
                .putExtra(CameraActivity.EXTRA_IP, address),
        )
    }
}

private class MasterAdapter(
    private val onClick: (DiscoveredMaster) -> Unit,
) : RecyclerView.Adapter<MasterAdapter.Holder>() {
    private val items = mutableListOf<DiscoveredMaster>()

    fun submit(next: List<DiscoveredMaster>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_master, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val master = items[position]
        holder.title.text = master.label()
        val token = if (master.hasToken) holder.itemView.context.getString(R.string.with_token) else ""
        holder.subtitle.text = master.uri + token
        holder.itemView.setOnClickListener { onClick(master) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
    }
}
