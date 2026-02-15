package com.slm.wnet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.slm.wnet.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val STRATEGY = Strategy.P2P_CLUSTER

    private var myName = ""
    private var myImageBitmap: Bitmap? = null
    private var myEncodedImage: String = ""

    // بيانات الاتصال
    data class Endpoint(val id: String, var name: String, var imageBitmap: Bitmap? = null)
    data class Message(val text: String, val isMe: Boolean, val timestamp: Long)

    private val connectedEndpoints = mutableListOf<Endpoint>()
    private val chatsHistory = HashMap<String, MutableList<Message>>()
    private lateinit var usersAdapter: UsersAdapter
    private lateinit var chatAdapter: ChatAdapter
    private var currentChatEndpointId: String? = null

    // اختيار الصورة (مع إصلاح المعاينة)
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, it)
            // تصغير الصورة لتجنب بطء الشبكة
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 200, 200, true)
            myImageBitmap = scaledBitmap
            
            // إصلاح مظهر المعاينة
            binding.imgProfilePreview.setImageBitmap(scaledBitmap)
            binding.imgProfilePreview.setPadding(0,0,0,0) // إزالة الحواف لتملأ الدائرة
            binding.imgProfilePreview.scaleType = ImageView.ScaleType.CENTER_CROP // ملء الدائرة
            
            myEncodedImage = encodeImage(scaledBitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // تشغيل الخدمة للبقاء في الخلفية
        val serviceIntent = Intent(this, WnetService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        checkSavedSession()
        setupUI()
        setupRecyclerViews()
    }

    private fun checkSavedSession() {
        val prefs = getSharedPreferences("WnetPrefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("name", null)
        val savedImage = prefs.getString("image", null)

        if (savedName != null) {
            myName = savedName
            myEncodedImage = savedImage ?: ""
            if (myEncodedImage.isNotEmpty()) {
                myImageBitmap = decodeImage(myEncodedImage)
                binding.imgMySmallProfile.setImageBitmap(myImageBitmap)
                binding.imgMySmallProfile.scaleType = ImageView.ScaleType.CENTER_CROP
            }
            binding.tvMyHeaderName.text = myName
            startAppLogic()
        }
    }

    private fun setupUI() {
        binding.imgProfilePreview.setOnClickListener { pickImageLauncher.launch("image/*") }

        binding.btnConnect.setOnClickListener {
            val nameInput = binding.etMyName.text.toString()
            if (nameInput.isNotBlank()) {
                myName = nameInput
                val prefs = getSharedPreferences("WnetPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("name", myName).putString("image", myEncodedImage).apply()
                
                binding.tvMyHeaderName.text = myName
                if (myImageBitmap != null) {
                    binding.imgMySmallProfile.setImageBitmap(myImageBitmap)
                    binding.imgMySmallProfile.scaleType = ImageView.ScaleType.CENTER_CROP
                }
                startAppLogic()
            }
        }
        
        // إصلاح الانهيار: ربط أزرار الشات بشكل صحيح
        val chatOverlay = findViewById<View>(R.id.chatContainer) // تأكد أن ID في layout_chat_overlay هو chatContainer
        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            chatOverlay.visibility = View.GONE
            binding.usersListContainer.visibility = View.VISIBLE
            currentChatEndpointId = null
        }
        
        findViewById<View>(R.id.btnSend)?.setOnClickListener {
            val etMsg = findViewById<android.widget.TextView>(R.id.etMessage)
            val text = etMsg.text.toString()
            if (text.isNotBlank() && currentChatEndpointId != null) {
                sendMessage(currentChatEndpointId!!, text)
                etMsg.text = ""
            }
        }
    }

    private fun startAppLogic() {
        binding.loginContainer.visibility = View.GONE
        binding.usersListContainer.visibility = View.VISIBLE

        // طلب الأذونات (مختصر)
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        else permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        
        startAdvertising()
        startDiscovery()
    }

    // --- Nearby Connections ---

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
            // اتصال تلقائي صامت (بدون بهرجة)
            Nearby.getConnectionsClient(this@MainActivity)
                .requestConnection(myName, endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // قبول تلقائي فوراً (Auto Accept) لحل مشكلة "طلب الاتصال البشع"
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                sendProfileInfo(endpointId)
                // إضافة المستخدم للقائمة فقط إذا لم يكن موجوداً
                if (!connectedEndpoints.any { it.id == endpointId }) {
                    connectedEndpoints.add(Endpoint(endpointId, "جاري التحميل...", null))
                    usersAdapter.notifyDataSetChanged()
                }
            }
        }
        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.removeAll { it.id == endpointId }
            usersAdapter.notifyDataSetChanged()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val data = String(bytes, StandardCharsets.UTF_8)
                if (data.startsWith("PROFILE:")) {
                    val parts = data.split(":", limit = 3)
                    if (parts.size == 3) {
                        val remoteName = parts[1]
                        val bitmap = decodeImage(parts[2])
                        val idx = connectedEndpoints.indexOfFirst { it.id == endpointId }
                        if (idx != -1) {
                            connectedEndpoints[idx].name = remoteName
                            connectedEndpoints[idx].imageBitmap = bitmap
                            usersAdapter.notifyItemChanged(idx)
                        }
                    }
                } else {
                    // Chat Message
                    val msgObj = Message(data, false, System.currentTimeMillis())
                    chatsHistory.getOrPut(endpointId) { mutableListOf() }.add(msgObj)
                    if (currentChatEndpointId == endpointId) {
                        chatAdapter.addMessage(msgObj)
                        findViewById<RecyclerView>(R.id.rvChatMessages).scrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendProfileInfo(endpointId: String) {
        val profilePayload = "PROFILE:$myName:$myEncodedImage"
        Nearby.getConnectionsClient(this).sendPayload(endpointId, Payload.fromBytes(profilePayload.toByteArray()))
    }

    private fun sendMessage(endpointId: String, message: String) {
        Nearby.getConnectionsClient(this).sendPayload(endpointId, Payload.fromBytes(message.toByteArray()))
        val msgObj = Message(message, true, System.currentTimeMillis())
        chatsHistory.getOrPut(endpointId) { mutableListOf() }.add(msgObj)
        chatAdapter.addMessage(msgObj)
        findViewById<RecyclerView>(R.id.rvChatMessages).scrollToPosition(chatAdapter.itemCount - 1)
    }

    // --- Helpers ---
    private fun encodeImage(bm: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 60, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP) // NO_WRAP مهم جداً
    }

    private fun decodeImage(base64Str: String): Bitmap? {
        return try {
            val decodedByte = Base64.decode(base64Str, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(decodedByte, 0, decodedByte.size)
        } catch (e: Exception) { null }
    }

    private fun setupRecyclerViews() {
        usersAdapter = UsersAdapter(connectedEndpoints) { endpoint ->
            openChat(endpoint)
        }
        binding.rvUsers.layoutManager = LinearLayoutManager(this)
        binding.rvUsers.adapter = usersAdapter

        chatAdapter = ChatAdapter(mutableListOf())
        val rvChat = findViewById<RecyclerView>(R.id.rvChatMessages)
        rvChat.layoutManager = LinearLayoutManager(this)
        rvChat.adapter = chatAdapter
    }

    private fun openChat(endpoint: Endpoint) {
        currentChatEndpointId = endpoint.id
        findViewById<TextView>(R.id.tvChatUserName).text = endpoint.name
        val messages = chatsHistory.getOrPut(endpoint.id) { mutableListOf() }
        chatAdapter.updateData(messages)
        
        binding.usersListContainer.visibility = View.GONE
        findViewById<View>(R.id.chatContainer).visibility = View.VISIBLE // هذا كان سبب الانهيار إذا كان null
    }
    
    // --- Adapters ---
    class UsersAdapter(private val users: List<Endpoint>, private val onClick: (Endpoint) -> Unit) :
        RecyclerView.Adapter<UsersAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvUserName)
            val img: ImageView = v.findViewById(R.id.imgUserAvatar)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user, parent, false)
            return Holder(view)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val user = users[position]
            holder.name.text = user.name
            if (user.imageBitmap != null) {
                holder.img.setImageBitmap(user.imageBitmap)
            } else {
                holder.img.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            holder.itemView.setOnClickListener { onClick(user) }
        }
        override fun getItemCount() = users.size
    }

    class ChatAdapter(private var messages: MutableList<Message>) : 
        RecyclerView.Adapter<ChatAdapter.Holder>() {
        fun updateData(newMessages: MutableList<Message>) {
            messages = newMessages
            notifyDataSetChanged()
        }
        fun addMessage(msg: Message) {
            notifyItemInserted(messages.size - 1)
        }
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val msgText: TextView = v.findViewById(android.R.id.text1)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            return Holder(view)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val msg = messages[position]
            holder.msgText.text = msg.text
            (holder.msgText as TextView).setTextColor(if(msg.isMe) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
            holder.msgText.textAlignment = if(msg.isMe) View.TEXT_ALIGNMENT_VIEW_END else View.TEXT_ALIGNMENT_VIEW_START
        }
        override fun getItemCount() = messages.size
    }
}
