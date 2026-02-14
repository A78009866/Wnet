package com.slm.wnet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
// استيرادات Nearby المهمة
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
// استيراد الواجهة
import com.slm.wnet.databinding.ActivityMainBinding
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {
    // ... باقي الكود كما هو


    private lateinit var binding: ActivityMainBinding
    private val STRATEGY = Strategy.P2P_CLUSTER // استراتيجية الشبكة المتداخلة
    private var myName = ""
    
    // قائمة الأجهزة المتصلة والقريبة
    private val connectedEndpoints = mutableListOf<String>()
    private val nearbyDevices = mutableListOf<Endpoint>()
    private lateinit var deviceAdapter: DeviceAdapter

    data class Endpoint(val id: String, val name: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()

        binding.btnGoOnline.setOnClickListener {
            myName = binding.etMyName.text.toString()
            if (myName.isNotEmpty()) {
                requestPermissionsAndStart()
            } else {
                Toast.makeText(this, "يرجى كتابة اسمك أولاً", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSend.setOnClickListener {
            val msg = binding.etMessage.text.toString()
            if (msg.isNotEmpty() && connectedEndpoints.isNotEmpty()) {
                sendMessage(msg)
                binding.etMessage.text.clear()
            }
        }
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(nearbyDevices) { endpoint ->
            // عند الضغط على اسم جهاز، نطلب الاتصال به
            Nearby.getConnectionsClient(this).requestConnection(myName, endpoint.id, connectionLifecycleCallback)
        }
        binding.rvNeighbors.layoutManager = LinearLayoutManager(this)
        binding.rvNeighbors.adapter = deviceAdapter
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        startAdvertising()
        startDiscovery()
        binding.btnGoOnline.isEnabled = false
        binding.btnGoOnline.text = "جاري البحث..."
    }

    // --- Nearby Connections Logic ---

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(this).startAdvertising(
            myName, "com.slm.wnet", connectionLifecycleCallback, options
        )
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        Nearby.getConnectionsClient(this).startDiscovery(
            "com.slm.wnet", endpointDiscoveryCallback, options
        )
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // تم العثور على جهاز
            nearbyDevices.add(Endpoint(endpointId, info.endpointName))
            deviceAdapter.notifyDataSetChanged()
        }

        override fun onEndpointLost(endpointId: String) {
            nearbyDevices.removeAll { it.id == endpointId }
            deviceAdapter.notifyDataSetChanged()
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // قبول الاتصال تلقائياً للسهولة
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
            appendChat("جاري الاتصال بـ ${info.endpointName}...")
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                appendChat("تم الاتصال بنجاح!")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            appendChat("انقطع الاتصال.")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                val message = String(it, StandardCharsets.UTF_8)
                appendChat("الطرف الآخر: $message")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendMessage(message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        Nearby.getConnectionsClient(this).sendPayload(connectedEndpoints, Payload.fromBytes(bytes))
        appendChat("أنا: $message")
    }

    private fun appendChat(text: String) {
        runOnUiThread {
            binding.tvChatLog.append("\n$text")
        }
    }

    // --- RecyclerView Adapter ---
    class DeviceAdapter(
        private val devices: List<Endpoint>,
        private val onClick: (Endpoint) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.tvName.text = device.name
            holder.itemView.setOnClickListener { onClick(device) }
        }

        override fun getItemCount() = devices.size
    }
}
