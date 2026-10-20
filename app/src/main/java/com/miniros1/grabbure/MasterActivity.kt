package com.miniros1.grabbure

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miniros1.grabbure.databinding.ActivityMasterBinding
import kotlin.system.exitProcess

class MasterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMasterBinding
    private lateinit var adapter: MasterAdapter
    private val store by lazy { MasterStore(this) }
    private var heard = emptyList<DiscoveredMaster>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var keepNetwork = false
    private var openedPending = false
    private val pickBagFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
        val folder = BagFolder.pathForTree(uri)
        if (folder == null || !(folder.isDirectory || folder.mkdirs()) || !folder.canWrite()) {
            Toast.makeText(this, R.string.bag_folder_unavailable, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        BagFolder.save(this, folder)
        showBagFolder()
    }
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
        adapter = MasterAdapter(
            onOpen = { master ->
                if (master.bag) openBag() else choose(master.local, master.uri, master.label)
            },
            onFavorite = { master ->
                if (master.favorite) store.clearFavorite() else store.setFavorite(master.uri, master.label)
                showMasters()
            },
            onRemove = { master ->
                store.remove(master.uri)
                showMasters()
            },
        )
        binding.masters.layoutManager = LinearLayoutManager(this)
        binding.masters.adapter = adapter
        binding.addMaster.setOnClickListener { addManual() }
        binding.phoneName.setText(PhoneName.load(this))
        showBagFolder()
        binding.chooseBagFolder.setOnClickListener { pickBagFolder.launch(null) }
        showMasters()
        binding.showLog.setOnCheckedChangeListener { _, checked ->
            binding.logScroll.visibility = if (checked) View.VISIBLE else View.GONE
            try {
                NativeBridge.setVerbose(checked)
            } catch (_: UnsatisfiedLinkError) {
            }
            flushLog()
        }
    }

    private var lastMasters = "\u0000"

    override fun onStart() {
        super.onStart()
        AppLog.line(bindWifi())
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi?.createMulticastLock("grabbure")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        AppLog.line(if (multicastLock?.isHeld == true) "Multicast lock held" else "Multicast lock not held")
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
        AppLog.line(if (address == null) "No Wi-Fi IPv4 address" else "Phone address $address")
        if (error != null) {
            binding.status.text = error
            AppLog.line(error)
            flushLog()
        } else {
            AppLog.line("Discovery started")
            handler.post(poll)
        }
        if (!openedPending) {
            openedPending = true
            val pending = store.takePending()
            if (pending != null) {
                binding.phoneName.setText(PhoneName.sanitize(pending.phone))
                choose(pending.local, pending.uri, pending.label)
            }
        }
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        try {
            NativeBridge.discoveryStop()
        } catch (_: UnsatisfiedLinkError) {
        }
        AppLog.line("Discovery stopped")
        if (!keepNetwork) {
            multicastLock?.let { if (it.isHeld) it.release() }
            multicastLock = null
            getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
        }
        super.onStop()
    }

    private fun bindWifi(): String {
        val connectivity = getSystemService(ConnectivityManager::class.java)
            ?: return "No connectivity service"
        val wifi = connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        if (wifi == null)
            return "No Wi-Fi network to bind"
        connectivity.bindProcessToNetwork(wifi)
        return "Bound process to Wi-Fi"
    }

    private fun refresh() {
        flushLog()
        heard = NativeBridge.discoveryPoll().toList()
        showMasters()
        val summary = heard.joinToString { master ->
            master.uri.ifBlank { master.label() }
        }
        if (summary != lastMasters) {
            lastMasters = summary
            AppLog.line(if (heard.isEmpty()) "No masters heard" else "Masters: $summary")
            flushLog()
        }
        binding.status.text = if (heard.isEmpty()) {
            getString(R.string.listening)
        } else {
            resources.getQuantityString(R.plurals.masters_found, heard.size, heard.size)
        }
    }

    private fun showMasters() {
        val favorite = store.favoriteUri()
        val saved = store.saved().associateBy { it.uri }
        val merged = linkedMapOf<String, KnownMaster>()
        for (item in saved.values) {
            merged[item.uri] = KnownMaster(
                uri = item.uri,
                label = item.label.ifBlank { item.uri },
                manual = item.manual,
                favorite = item.uri == favorite,
                saved = true,
                hasToken = false,
            )
        }
        for (item in heard) {
            if (item.uri.isBlank()) {
                merged["uuid:" + item.uuid] = KnownMaster(
                    uri = "",
                    label = item.label(),
                    manual = false,
                    favorite = false,
                    saved = false,
                    hasToken = item.hasToken,
                )
                continue
            }
            val kept = saved[item.uri]
            merged[item.uri] = KnownMaster(
                uri = item.uri,
                label = item.label(),
                manual = kept?.manual == true,
                favorite = item.uri == favorite,
                saved = kept != null,
                hasToken = item.hasToken,
            )
        }
        val ordered = merged.values.sortedWith(
            compareByDescending<KnownMaster> { it.favorite }
                .thenByDescending { it.manual }
                .thenBy { it.label },
        )
        val bag = KnownMaster(
            uri = "",
            label = getString(R.string.record_bag),
            manual = false,
            favorite = false,
            saved = false,
            hasToken = false,
            bag = true,
        )
        val local = KnownMaster(
            uri = "",
            label = getString(R.string.local_master),
            manual = false,
            favorite = false,
            saved = false,
            hasToken = false,
            local = true,
        )
        adapter.submit(listOf(bag, local) + ordered)
    }

    private fun flushLog() {
        if (!binding.showLog.isChecked) return
        try {
            AppLog.appendNative(NativeBridge.drainLog())
        } catch (_: UnsatisfiedLinkError) {
        }
        val text = AppLog.text()
        if (binding.logView.text.toString() != text) {
            binding.logView.text = text
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun addManual() {
        val parsed = parseMaster(binding.manualMaster.text.toString())
        if (parsed == null) {
            Toast.makeText(this, R.string.bad_master_address, Toast.LENGTH_LONG).show()
            AppLog.line("Rejected master address \"" + binding.manualMaster.text + "\"")
            flushLog()
            return
        }
        store.addManual(parsed.first, parsed.second)
        binding.manualMaster.text.clear()
        AppLog.line("Saved manual master " + parsed.second)
        showMasters()
        flushLog()
    }

    private fun choose(local: Boolean, uri: String, label: String) {
        if (!local && uri.isBlank()) {
            Toast.makeText(this, R.string.master_without_address, Toast.LENGTH_SHORT).show()
            return
        }
        val phone = PhoneName.sanitize(binding.phoneName.text.toString())
        binding.phoneName.setText(phone)
        if (restartIfNeeded(local, uri, label, phone)) return
        if (local) startLocal() else openSession(uri, label)
    }

    /**
     * The process can join only one master. A different master or phone name is saved,
     * then a helper process brings the app back and [onStart] opens that connection.
     */
    private fun restartIfNeeded(local: Boolean, uri: String, label: String, phone: String): Boolean {
        val started = try {
            NativeBridge.nodeStarted()
        } catch (_: UnsatisfiedLinkError) {
            return false
        }
        if (!started) return false
        val currentPhone = try {
            NativeBridge.nodeName()
        } catch (_: UnsatisfiedLinkError) {
            ""
        }
        val runningLocal = try {
            NativeBridge.localMasterUri().isNotBlank()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        val currentUri = try {
            NativeBridge.nodeMaster()
        } catch (_: UnsatisfiedLinkError) {
            ""
        }
        val sameTarget = if (local) runningLocal else !runningLocal && currentUri == uri
        if (currentPhone == phone && sameTarget) return false
        store.setPending(local, uri, label, phone)
        binding.status.text = getString(R.string.restarting_connection)
        AppLog.line("Restarting to connect to " + if (local) label else uri)
        startActivity(
            Intent(this, RestartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    private fun startLocal() {
        val address = NetInfo.wifiIpv4(this)
        if (address == null) {
            Toast.makeText(this, R.string.no_wifi_address, Toast.LENGTH_LONG).show()
            return
        }
        val phone = PhoneName.save(this, binding.phoneName.text.toString())
        binding.phoneName.setText(phone)
        binding.status.text = getString(R.string.starting_local_master)
        Thread {
            val error = try {
                NativeBridge.startLocalMaster(address, phone)
            } catch (ex: UnsatisfiedLinkError) {
                ex.message ?: "Native library failed to load"
            }
            val uri = if (error == null) {
                try {
                    NativeBridge.localMasterUri()
                } catch (ex: UnsatisfiedLinkError) {
                    ex.message
                }
            } else {
                null
            }
            runOnUiThread {
                if (error != null || uri.isNullOrBlank()) {
                    val message = error ?: getString(R.string.starting_local_master)
                    binding.status.text = message
                    AppLog.line(message)
                    flushLog()
                    return@runOnUiThread
                }
                keepNetwork = true
                LocalMasterPump.start()
                AppLog.line("Local master $uri")
                openSession(uri, getString(R.string.local_master))
            }
        }.start()
    }

    private fun showBagFolder() {
        binding.bagFolder.text = getString(R.string.bag_folder_line, BagFolder.directory(this).absolutePath)
    }

    private fun openBag() {
        val phone = PhoneName.save(this, binding.phoneName.text.toString())
        binding.phoneName.setText(phone)
        val folder = BagFolder.directory(this)
        if (!folder.isDirectory || !folder.canWrite()) {
            Toast.makeText(this, R.string.bag_folder_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(Session.EXTRA_BAG, true)
                .putExtra(Session.EXTRA_BAG_DIR, folder.absolutePath)
                .putExtra(Session.EXTRA_LABEL, getString(R.string.record_bag))
                .putExtra(Session.EXTRA_PHONE, phone),
        )
    }

    private fun openSession(uri: String, label: String) {
        if (uri.isBlank()) {
            Toast.makeText(this, R.string.master_without_address, Toast.LENGTH_SHORT).show()
            return
        }
        val address = NetInfo.wifiIpv4(this)
        if (address == null) {
            Toast.makeText(this, R.string.no_wifi_address, Toast.LENGTH_LONG).show()
            return
        }
        val phone = PhoneName.save(this, binding.phoneName.text.toString())
        binding.phoneName.setText(phone)
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(Session.EXTRA_URI, uri)
                .putExtra(Session.EXTRA_LABEL, label)
                .putExtra(Session.EXTRA_IP, address)
                .putExtra(Session.EXTRA_PHONE, phone),
        )
    }
}

/** Calls `Master.update` so discovery and node checks keep running. */
private object LocalMasterPump {
    private const val PERIOD_MS = 100L
    private val thread = android.os.HandlerThread("master-update").apply { start() }
    private val handler = Handler(thread.looper)
    private val tick = object : Runnable {
        override fun run() {
            try {
                NativeBridge.masterUpdate()
            } catch (_: UnsatisfiedLinkError) {
            }
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    fun start() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
}

private fun parseMaster(raw: String): Pair<String, String>? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (text.startsWith("http://")) {
        val uri = if (text.endsWith("/")) text else "$text/"
        val label = text.removePrefix("http://").trimEnd('/')
        return uri to label
    }
    val colon = text.lastIndexOf(':')
    val host: String
    val port: Int
    if (colon > 0 && text.indexOf(':') == colon) {
        host = text.substring(0, colon)
        port = text.substring(colon + 1).toIntOrNull() ?: return null
    } else {
        host = text
        port = 11311
    }
    if (host.isBlank() || port !in 1..65535) return null
    return "http://$host:$port/" to "$host:$port"
}

private data class KnownMaster(
    val uri: String,
    val label: String,
    val manual: Boolean,
    val favorite: Boolean,
    val saved: Boolean,
    val hasToken: Boolean,
    val local: Boolean = false,
    val bag: Boolean = false,
)

private class MasterAdapter(
    private val onOpen: (KnownMaster) -> Unit,
    private val onFavorite: (KnownMaster) -> Unit,
    private val onRemove: (KnownMaster) -> Unit,
) : RecyclerView.Adapter<MasterAdapter.Holder>() {
    private val items = mutableListOf<KnownMaster>()

    fun submit(next: List<KnownMaster>) {
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
        val context = holder.itemView.context
        holder.title.text = master.label
        holder.itemView.setOnClickListener { onOpen(master) }
        holder.favorite.visibility = if (master.local || master.bag || master.uri.isBlank()) View.GONE else View.VISIBLE
        holder.favorite.setIconResource(
            if (master.favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
        )
        holder.favorite.contentDescription = context.getString(
            if (master.favorite) R.string.unfavorite else R.string.favorite,
        )
        holder.favorite.setOnClickListener { onFavorite(master) }
        holder.remove.visibility = if (master.saved) View.VISIBLE else View.GONE
        holder.remove.setOnClickListener { onRemove(master) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val favorite: com.google.android.material.button.MaterialButton = view.findViewById(R.id.favorite)
        val remove: com.google.android.material.button.MaterialButton = view.findViewById(R.id.remove)
    }
}
