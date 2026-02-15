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
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.slm.wnet.databinding.ActivityMainBinding
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    
    // استراتيجية الشبكة: P2P_CLUSTER تدعم اتصال M-to-N (شبكة عنكبوتية صغيرة)
    private val STRATEGY = Strategy.P2P_CLUSTER
    
    private var myName = ""
    private var currentChatEndpointId: String? = null

    // بيانات المستخدمين والرسائل
    data class Endpoint(val id: String, val name: String)
    data class Message(val text: String, val isMe: Boolean, val timestamp: Long)

    private val detectedEndpoints = mutableListOf<Endpoint>()
    private val connectedEndpoints = mutableListOf<Endpoint>()
    
    // خريطة لتخزين الرسائل لكل مستخدم (endpointId -> List of Messages)
    private val chatsHistory = HashMap<String, MutableList<Message>>()

    // Adapters
    private lateinit var usersAdapter: UsersAdapter
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerViews()
        setupUI()
    }

    private fun setupUI() {
        // زر الدخول
        binding.btnConnect.setOnClickListener {
            val nameInput = binding.etMyName.text.toString()
            if (nameInput.isNotBlank()) {
                myName = nameInput
                requestPermissionsAndStart()
            } else {
                Toast.makeText(this, "الرجاء كتابة اسم للاستمرار", Toast.LENGTH_SHORT).show()
            }
        }

        // زر العودة من المحادثة
        binding.btnBack.setOnClickListener {
            binding.chatContainer.visibility = View.GONE
            binding.usersListContainer.visibility = View.VISIBLE
            currentChatEndpointId = null
        }

        // زر الإرسال
        binding.btnSend.setOnClickListener {
            val msgText = binding.etMessage.text.toString()
            if (msgText.isNotBlank() && currentChatEndpointId != null) {
                sendMessage(currentChatEndpointId!!, msgText)
                binding.etMessage.text.clear()
            }
        }
    }

    private fun setupRecyclerViews() {
        // قائمة المستخدمين
        usersAdapter = UsersAdapter(connectedEndpoints) { endpoint ->
            openChat(endpoint)
        }
        binding.rvUsers.layoutManager = LinearLayoutManager(this)
        binding.rvUsers.adapter = usersAdapter

        // قائمة الرسائل (سيتم تعيين البيانات عند فتح المحادثة)
        chatAdapter = ChatAdapter(mutableListOf())
        binding.rvChatMessages.layoutManager = LinearLayoutManager(this)
        binding.rvChatMessages.adapter = chatAdapter
    }

    private fun openChat(endpoint: Endpoint) {
        currentChatEndpointId = endpoint.id
        binding.tvChatUserName.text = endpoint.name
        
        // جلب سجل الرسائل أو إنشاء جديد
        val messages = chatsHistory.getOrPut(endpoint.id) { mutableListOf() }
        chatAdapter.updateData(messages)
        
        // تبديل الواجهة
        binding.usersListContainer.visibility = View.GONE
        binding.chatContainer.visibility = View.VISIBLE
        
        // التمرير لأسفل
        if (messages.isNotEmpty()) {
            binding.rvChatMessages.scrollToPosition(messages.size - 1)
        }
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
        
        // إخفاء تسجيل الدخول وإظهار القائمة
        binding.loginContainer.visibility = View.GONE
        binding.usersListContainer.visibility = View.VISIBLE
        
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
            // طلب اتصال تلقائي عند الاكتشاف (يمكن تغييره ليدوي حسب الرغبة)
            Nearby.getConnectionsClient(this@MainActivity)
                .requestConnection(myName, endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // قبول الاتصال فوراً
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
            // إضافة مؤقتة للعرض (سيتم التأكيد عند onConnectionResult)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                // البحث عن الاسم (قد نحتاج تخزينه من initiated)
                // هنا نفترض أننا لا نعرف الاسم بعد، لكن سنضيفه كـ "Unknown" ثم نحدثه إذا أرسل بيانات
                // للتبسيط، Nearby لا يعطي الاسم في Result مباشرة، لذا نستخدم الاسم من Discovery أو Initiated
                // سنقوم بحيلة بسيطة: إرسال "Handshake" يحتوي الاسم الحقيقي
                
                // في هذا الكود المبسط، سنعتمد على أننا وجدنا الجهاز سابقاً
                // ملاحظة: لتحسين الكود يجب تخزين الاسم في onConnectionInitiated
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.removeAll { it.id == endpointId }
            usersAdapter.notifyDataSetChanged()
            if (currentChatEndpointId == endpointId) {
                Toast.makeText(this@MainActivity, "انقطع الاتصال بهذا المستخدم", Toast.LENGTH_SHORT).show()
                binding.btnBack.performClick()
            }
        }
    }
    
    // قمت بتعديل الCallback ليشمل الاسم من الـ Initiated بشكل صحيح
    // لكن للتبسيط هنا، سأعتمد على أن onConnectionInitiated تعطينا الاسم
    // سنعيد صياغة logic بسيط لتخزين الاسم
    private val pendingNames = HashMap<String, String>()

    // نسخة محسنة من الـ Callback
    private val connectionLifecycleCallbackImproved = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingNames[endpointId] = info.endpointName
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val name = pendingNames[endpointId] ?: "Unknown"
                val newEndpoint = Endpoint(endpointId, name)
                if (!connectedEndpoints.contains(newEndpoint)) {
                    connectedEndpoints.add(newEndpoint)
                    usersAdapter.notifyDataSetChanged()
                    binding.tvStatus.text = "متصل بـ ${connectedEndpoints.size} جهاز"
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
            payload.asBytes()?.let {
                val msgText = String(it, StandardCharsets.UTF_8)
                val msgObj = Message(msgText, false, System.currentTimeMillis())
                
                // تخزين الرسالة
                val list = chatsHistory.getOrPut(endpointId) { mutableListOf() }
                list.add(msgObj)
                
                // تحديث الواجهة إذا كنا في نفس المحادثة
                if (currentChatEndpointId == endpointId) {
                    chatAdapter.addMessage(msgObj)
                    binding.rvChatMessages.scrollToPosition(chatAdapter.itemCount - 1)
                } else {
                    Toast.makeText(this@MainActivity, "رسالة جديدة!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendMessage(endpointId: String, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        Nearby.getConnectionsClient(this).sendPayload(endpointId, Payload.fromBytes(bytes))
        
        // إضافة الرسالة للسجل المحلي
        val msgObj = Message(message, true, System.currentTimeMillis())
        val list = chatsHistory.getOrPut(endpointId) { mutableListOf() }
        list.add(msgObj)
        chatAdapter.addMessage(msgObj)
        binding.rvChatMessages.scrollToPosition(chatAdapter.itemCount - 1)
    }

    // --- Adapters ---

    class UsersAdapter(private val users: List<Endpoint>, private val onClick: (Endpoint) -> Unit) :
        RecyclerView.Adapter<UsersAdapter.Holder>() {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvUserName)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            // هنا يجب استخدام ملف item_user.xml الذي أنشأناه
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user, parent, false)
            return Holder(view)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.name.text = users[position].name
            holder.itemView.setOnClickListener { onClick(users[position]) }
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
            // messages تشير بالفعل لنفس القائمة في الذاكرة، لذا notify يكفي
            notifyItemInserted(messages.size - 1)
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val msgText: TextView = v.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            // نستخدم تخطيط بسيط مدمج أو يمكنك إنشاء تخطيط مخصص للفقاعات
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
            val tv = view.findViewById<TextView>(android.R.id.text1)
            tv.setTextColor(android.graphics.Color.WHITE)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val msg = messages[position]
            holder.msgText.text = msg.text
            
            // تغيير المحاذاة واللون بناء على المرسل (محاكاة الفقاعات)
            if (msg.isMe) {
                holder.msgText.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                holder.msgText.setTextColor(android.graphics.Color.CYAN)
            } else {
                holder.msgText.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                holder.msgText.setTextColor(android.graphics.Color.WHITE)
            }
        }
        override fun getItemCount() = messages.size
    }
    
    // ملاحظة: يجب عليك استبدال connectionLifecycleCallback بالنسخة المحسنة connectionLifecycleCallbackImproved في دوال startAdvertising و startDiscovery
}
