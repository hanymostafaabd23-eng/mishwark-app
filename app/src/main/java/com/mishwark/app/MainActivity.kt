package com.mishwark.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Gravity
import android.text.InputType
import android.util.Log
import android.widget.*
import java.util.Locale
import java.util.concurrent.TimeUnit

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.FirebaseUser

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue

import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

class MainActivity : Activity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private var verificationId: String? = null
    private var checkingAccount = false

    // نوع الحساب المختار
    private var selectedAccountType = "auto"

    private val LOCATION_PERMISSION_CODE = 200
    private var pendingPickup: EditText? = null
    private var locationListener: LocationListener? = null
    private val locationHandler = Handler(Looper.getMainLooper())

    private var mapView: MapView? = null
    private var mapLibreMap: MapLibreMap? = null
    private var activeMapSelection: EditText? = null

    private val adminUid = "b7Qe8kkkf1U0cb2AV3xBz76k6Vv1"

    private val osmStyleJson = """
    {
      "version": 8,
      "sources": {
        "osm": {
          "type": "raster",
          "tiles": [
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
          ],
          "tileSize": 256,
          "attribution": "© OpenStreetMap contributors"
        }
      },
      "layers": [
        {
          "id": "osm",
          "type": "raster",
          "source": "osm"
        }
      ]
    }
    """

    private fun saveError(message: String, error: Throwable? = null) {
        try {
            val details = buildString {
                append("\n--- MISHWARK ERROR ---\n")
                append(message)
                append("\n")
                append(error?.stackTraceToString() ?: "")
            }

            openFileOutput("mishwark_errors.txt", Context.MODE_APPEND)
                .bufferedWriter()
                .use { it.write(details) }

            Log.e("MISHWARK_ERROR", message, error)

        } catch (e: Exception) {
            Log.e("MISHWARK_ERROR", "Could not save error", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            saveError("APP CRASHED", error)
            oldHandler?.uncaughtException(thread, error)
        }

        try {
            val file = getFileStreamPath("mishwark_errors.txt")

            if (file.exists()) {
                val oldErrors = file.readText()

                AlertDialog.Builder(this)
                    .setTitle("تقرير أخطاء مشوارك")
                    .setMessage(oldErrors.takeLast(3000))
                    .setPositiveButton("موافق", null)
                    .show()
            }
        } catch (e: Exception) {
            Log.e("MISHWARK_ERROR", "Reading error file failed", e)
        }

        try {
            MapLibre.getInstance(this)

            val mapClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                        .newBuilder()
                        .header("User-Agent", "Mishwark/1.0 Android")
                        .build()

                    chain.proceed(request)
                }
                .build()

            HttpRequestUtil.setOkHttpClient(mapClient)

            FirebaseApp.initializeApp(this)
                ?: throw IllegalStateException("Firebase initialization failed")

            auth = FirebaseAuth.getInstance()
            db = FirebaseFirestore.getInstance()

            showCorrectScreen()

        } catch (e: Exception) {
            saveError("Application startup failed", e)
            showError("خطأ في تشغيل التطبيق: ${e.message}")
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // =========================
    // تحديد الشاشة حسب نوع الحساب
    // =========================

    private fun showCorrectScreen() {

        val user = auth.currentUser

        if (user == null) {
            checkingAccount = false
            showRoleSelectionScreen()
            return
        }

        if (checkingAccount) return

        checkingAccount = true

        if (user.uid == adminUid) {
            checkingAccount = false
            showAdminScreen()
            return
        }

        if (selectedAccountType == "driver") {
            checkDriverAccount(user)
            return
        }

        if (selectedAccountType == "passenger") {
            checkPassengerAccount(user)
            return
        }

        // عند فتح التطبيق مرة أخرى نتحقق من الحساب المسجل سابقاً
        db.collection("drivers")
            .document(user.uid)
            .get()
            .addOnSuccessListener { driverDocument ->

                if (driverDocument.exists()) {
                    checkingAccount = false

                    val status =
                        driverDocument.getString("status") ?: "pending"

                    showDriverScreen(status)
                } else {
                    checkPassengerAccount(user)
                }
            }
            .addOnFailureListener { error ->

                checkingAccount = false

                saveError("Checking driver account failed", error)

                showError(
                    "تعذر التحقق من حساب السائق. تحقق من الاتصال وحاول مرة أخرى."
                )
            }
    }

    // =========================
    // التحقق من حساب السائق
    // =========================

    private fun checkDriverAccount(user: FirebaseUser) {

        db.collection("drivers")
            .document(user.uid)
            .get()
            .addOnSuccessListener { document ->

                checkingAccount = false

                if (document.exists()) {
                    val status =
                        document.getString("status") ?: "pending"

                    showDriverScreen(status)
                } else {
                    showDriverRegistrationScreen()
                }
            }
            .addOnFailureListener { error ->

                checkingAccount = false

                saveError("Checking driver account failed", error)

                showError(
                    "تعذر التحقق من حساب السائق: ${error.message}"
                )
            }
    }

    // =========================
    // التحقق من حساب الراكب
    // =========================

    private fun checkPassengerAccount(user: FirebaseUser) {

        db.collection("users")
            .document(user.uid)
            .get()
            .addOnSuccessListener { passengerDocument ->

                if (passengerDocument.exists()) {

                    val role = passengerDocument.getString("role")

                    if (role != null && role != "passenger") {
                        checkingAccount = false
                        showError(
                            "نوع الحساب غير معروف، يرجى مراجعة الإدارة"
                        )
                        return@addOnSuccessListener
                    }

                    val status =
                        passengerDocument.getString("status") ?: "active"

                    if (status == "suspended") {

                        checkingAccount = false

                        AlertDialog.Builder(this)
                            .setTitle("الحساب موقوف")
                            .setMessage(
                                "تم إيقاف حسابك. يرجى التواصل مع الإدارة."
                            )
                            .setCancelable(false)
                            .setPositiveButton("تسجيل الخروج") { _, _ ->
                                auth.signOut()
                                selectedAccountType = "auto"
                                showRoleSelectionScreen()
                            }
                            .show()

                        return@addOnSuccessListener
                    }

                    checkingAccount = false
                    showHomeScreen()

                } else {

                    val passengerData = hashMapOf<String, Any>(
                        "uid" to user.uid,
                        "phone" to (user.phoneNumber ?: ""),
                        "role" to "passenger",
                        "status" to "active",
                        "createdAt" to FieldValue.serverTimestamp()
                    )

                    db.collection("users")
                        .document(user.uid)
                        .set(passengerData)
                        .addOnSuccessListener {

                            checkingAccount = false

                            showError(
                                "تم إنشاء حسابك في مشوارك بنجاح"
                            )

                            showHomeScreen()
                        }
                        .addOnFailureListener { error ->

                            checkingAccount = false

                            saveError("Passenger creation failed", error)

                            showError(
                                "تعذر إنشاء حساب الراكب: ${error.message}"
                            )
                        }
                }
            }
            .addOnFailureListener { error ->

                checkingAccount = false

                saveError("Checking passenger account failed", error)

                showError(
                    "تعذر التحقق من حساب الراكب: ${error.message}"
                )
            }
    }

    private fun createLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.rgb(10, 35, 70))
        }
    }

    // =========================
    // شاشة اختيار نوع الحساب
    // =========================

    private fun showRoleSelectionScreen() {

        val layout = createLayout()

        val title = TextView(this).apply {
            text = "مشوارك"
            textSize = 40f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        val slogan = TextView(this).apply {
            text = "مشوارك في إيدك"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(10, 10, 10, 40)
        }

        val question = TextView(this).apply {
            text = "اختر نوع الحساب"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(10, 15, 10, 25)
        }

        val driverButton = Button(this).apply {
            text = "🚕 تسجيل سائق"
            textSize = 21f
            setTextColor(Color.rgb(10, 35, 70))
            setBackgroundColor(Color.YELLOW)
            setPadding(15, 18, 15, 18)
        }

        val passengerButton = Button(this).apply {
            text = "👤 تسجيل راكب"
            textSize = 21f
            setTextColor(Color.rgb(10, 35, 70))
            setBackgroundColor(Color.WHITE)
            setPadding(15, 18, 15, 18)
        }

        layout.addView(title)
        layout.addView(slogan)
        layout.addView(question)
        layout.addView(driverButton)
        layout.addView(passengerButton)

        setContentView(layout)

        driverButton.setOnClickListener {
            selectedAccountType = "driver"
            showPhoneScreen()
        }

        passengerButton.setOnClickListener {
            selectedAccountType = "passenger"
            showPhoneScreen()
        }
    }

    // =========================
    // شاشة تسجيل الهاتف
    // =========================

    private fun showPhoneScreen() {

        val layout = createLayout()

        val title = TextView(this).apply {
            text = "مشوارك"
            textSize = 38f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
        }

        val slogan = TextView(this).apply {
            text = "مشوارك في إيدك"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val accountType = TextView(this).apply {
            text = if (selectedAccountType == "driver")
                "تسجيل حساب سائق"
            else
                "تسجيل حساب راكب"

            textSize = 20f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        val phone = EditText(this).apply {
            hint = "أدخل رقم هاتفك 01xxxxxxxxx"
            textSize = 18f
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(20, 15, 20, 15)
        }

        val send = Button(this).apply {
            text = "إرسال كود التأكيد"
        }

        val back = Button(this).apply {
            text = "العودة لاختيار نوع الحساب"
        }

        layout.addView(title)
        layout.addView(slogan)
        layout.addView(accountType)
        layout.addView(phone)
        layout.addView(send)
        layout.addView(back)

        setContentView(layout)

        send.setOnClickListener {
            val number = phone.text.toString().trim()

            if (!number.matches(Regex("^01[0125][0-9]{8}$"))) {
                showError("أدخل رقم مصري صحيح")
                return@setOnClickListener
            }

            sendCode("+20" + number.drop(1), send)
        }

        back.setOnClickListener {
            selectedAccountType = "auto"
            showRoleSelectionScreen()
        }
    }

    // نهاية الجزء الأول
        private fun sendCode(number: String, send: Button) {
        send.isEnabled = false

        try {
            val callbacks =
                object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

                    override fun onVerificationCompleted(
                        credential: PhoneAuthCredential
                    ) {
                        signIn(credential)
                    }

                    override fun onVerificationFailed(e: FirebaseException) {
                        saveError("Phone verification failed", e)

                        runOnUiThread {
                            send.isEnabled = true
                            showError("فشل الإرسال: ${e.message}")
                        }
                    }

                    override fun onCodeSent(
                        id: String,
                        token: PhoneAuthProvider.ForceResendingToken
                    ) {
                        verificationId = id

                        runOnUiThread {
                            showCodeScreen()
                        }
                    }

                    override fun onCodeAutoRetrievalTimeOut(id: String) {
                        verificationId = id
                    }
                }

            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(number)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(callbacks)
                .build()

            PhoneAuthProvider.verifyPhoneNumber(options)

        } catch (e: Exception) {
            saveError("Sending code crashed", e)
            send.isEnabled = true
            showError("حدث خطأ: ${e.message}")
        }
    }

    private fun showCodeScreen() {
        val layout = createLayout()

        val title = TextView(this).apply {
            text = "تأكيد رقم الهاتف"
            textSize = 26f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
        }

        val code = EditText(this).apply {
            hint = "أدخل كود SMS"
            textSize = 20f
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(20, 15, 20, 15)
        }

        val verify = Button(this).apply {
            text = "تأكيد الدخول"
        }

        val back = Button(this).apply {
            text = "العودة"
        }

        layout.addView(title)
        layout.addView(code)
        layout.addView(verify)
        layout.addView(back)

        setContentView(layout)

        verify.setOnClickListener {
            val enteredCode = code.text.toString().trim()
            val id = verificationId

            if (enteredCode.length != 6 || id == null) {
                showError("أدخل كود التأكيد المكون من 6 أرقام")
                return@setOnClickListener
            }

            verify.isEnabled = false
            signIn(PhoneAuthProvider.getCredential(id, enteredCode))
        }

        back.setOnClickListener {
            showPhoneScreen()
        }
    }

    private fun signIn(credential: PhoneAuthCredential) {
        try {
            auth.signInWithCredential(credential)
                .addOnCompleteListener(this) { task ->

                    if (task.isSuccessful) {
                        checkingAccount = false
                        showError("تم تسجيل الدخول بنجاح")
                        showCorrectScreen()
                    } else {
                        saveError("Sign in failed", task.exception)
                        showError(
                            "فشل تسجيل الدخول: ${task.exception?.message}"
                        )
                    }
                }

        } catch (e: Exception) {
            saveError("Sign in crashed", e)
            showError("خطأ في تسجيل الدخول: ${e.message}")
        }
    }

    // =========================
    // تسجيل السائق
    // =========================

    private fun showDriverRegistrationScreen() {
        val user = auth.currentUser ?: return

        val main = createLayout()
        val scroll = ScrollView(this)
        scroll.addView(main)

        fun addText(text: String, size: Float, color: Int) {
            val view = TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(10, 15, 10, 15)
            }
            main.addView(view)
        }

        fun addInput(hintText: String): EditText {
            val field = EditText(this).apply {
                hint = hintText
                textSize = 17f
                setTextColor(Color.BLACK)
                setHintTextColor(Color.GRAY)
                setSingleLine(true)
                setPadding(18, 15, 18, 15)
                setBackgroundColor(Color.WHITE)
            }

            main.addView(field)
            return field
        }

        addText("مشوارك", 35f, Color.YELLOW)
        addText("تسجيل بيانات السائق", 24f, Color.WHITE)

        val name = addInput("اسم السائق بالكامل")
        val phone = addInput("رقم الهاتف")

        phone.setText(user.phoneNumber ?: "")

        addText("اختر نوع المركبة", 18f, Color.YELLOW)

        val vehicleGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(15, 10, 15, 10)
        }

        val vehicles = listOf(
            "توك توك",
            "تاكسي",
            "سيارة خاصة",
            "موتوسيكل",
            "ميكروباص"
        )

        vehicles.forEachIndexed { index, vehicle ->
            val radio = RadioButton(this).apply {
                text = vehicle
                textSize = 18f
                setTextColor(Color.BLACK)
                id = 500 + index
            }
            vehicleGroup.addView(radio)
        }

        vehicleGroup.check(500)
        main.addView(vehicleGroup)

        val plate = addInput("رقم لوحة المركبة")

        val submit = Button(this).apply {
            text = "إرسال طلب التسجيل"
            textSize = 18f
            setBackgroundColor(Color.YELLOW)
            setTextColor(Color.rgb(10, 35, 70))
        }

        main.addView(submit)

        submit.setOnClickListener {
            val driverName = name.text.toString().trim()
            val driverPhone = phone.text.toString().trim()
            val plateNumber = plate.text.toString().trim()

            if (driverName.isEmpty()) {
                showError("اكتب اسم السائق")
                return@setOnClickListener
            }

            if (driverPhone.isEmpty()) {
                showError("اكتب رقم الهاتف")
                return@setOnClickListener
            }

            if (vehicleGroup.checkedRadioButtonId == -1) {
                showError("اختر نوع المركبة")
                return@setOnClickListener
            }

            if (plateNumber.isEmpty()) {
                showError("اكتب رقم لوحة المركبة")
                return@setOnClickListener
            }

            val selectedVehicle = findViewById<RadioButton>(
                vehicleGroup.checkedRadioButtonId
            ).text.toString()

            submit.isEnabled = false

            val driverData = hashMapOf<String, Any>(
                "uid" to user.uid,
                "name" to driverName,
                "phone" to driverPhone,
                "vehicleType" to selectedVehicle,
                "plateNumber" to plateNumber,
                "status" to "pending",
                "createdAt" to FieldValue.serverTimestamp()
            )

            db.collection("drivers")
                .document(user.uid)
                .set(driverData)
                .addOnSuccessListener {
                    showError("تم إرسال طلبك بنجاح")
                    showDriverScreen("pending")
                }
                .addOnFailureListener { error ->
                    submit.isEnabled = true
                    saveError("Driver registration failed", error)
                    showError("فشل إرسال الطلب: ${error.message}")
                }
        }

        val back = Button(this).apply {
            text = "العودة لاختيار نوع الحساب"
        }

        main.addView(back)

        back.setOnClickListener {
            auth.signOut()
            checkingAccount = false
            selectedAccountType = "auto"
            showRoleSelectionScreen()
        }

        setContentView(scroll)
    }

    // =========================
    // شاشة حالة السائق
    // =========================

    private fun showDriverScreen(status: String) {
        val main = createLayout()
        val scroll = ScrollView(this)
        scroll.addView(main)

        val title = TextView(this).apply {
            text = "مشوارك"
            textSize = 36f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        main.addView(title)

        val statusText = when (status) {
            "approved" -> "تم قبول طلبك! يمكنك العمل كسائق."
            "rejected" -> "نأسف، تم رفض طلب التسجيل."
            else -> "طلب التسجيل قيد المراجعة من الإدارة."
        }

        val message = TextView(this).apply {
            text = statusText
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(15, 35, 15, 35)
        }

        main.addView(message)

        if (status == "approved") {
            val approvedInfo = TextView(this).apply {
                text = "تم تفعيل حسابك كسائق في مشوارك."
                textSize = 18f
                setTextColor(Color.YELLOW)
                gravity = Gravity.CENTER
            }
            main.addView(approvedInfo)
        }

        val refresh = Button(this).apply {
            text = "تحديث حالة الطلب"
        }

        main.addView(refresh)

        refresh.setOnClickListener {
            val uid = auth.currentUser?.uid ?: return@setOnClickListener

            db.collection("drivers")
                .document(uid)
                .get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        showDriverScreen(
                            document.getString("status") ?: "pending"
                        )
                    } else {
                        selectedAccountType = "auto"
                        showRoleSelectionScreen()
                    }
                }
                .addOnFailureListener {
                    showError("تعذر تحديث حالة الطلب")
                }
        }

        val logout = Button(this).apply {
            text = "تسجيل الخروج"
        }

        main.addView(logout)

        logout.setOnClickListener {
            auth.signOut()
            checkingAccount = false
            selectedAccountType = "auto"
            showRoleSelectionScreen()
        }

        setContentView(scroll)
    }

    // =========================
    // لوحة الإدارة
    // =========================

    private fun showAdminScreen() {
        val main = createLayout()
        val scroll = ScrollView(this)
        scroll.addView(main)

        fun addTitle(text: String, size: Float) {
            val view = TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(Color.YELLOW)
                gravity = Gravity.CENTER
                setPadding(10, 15, 10, 15)
            }
            main.addView(view)
        }

        fun addButton(text: String, action: () -> Unit) {
            val button = Button(this).apply {
                this.text = text
                textSize = 18f
                setTextColor(Color.rgb(10, 35, 70))
                setBackgroundColor(Color.YELLOW)
            }

            main.addView(button)
            button.setOnClickListener { action() }
        }

        addTitle("مشوارك", 36f)
        addTitle("لوحة تحكم الإدارة", 25f)
        addTitle("أهلاً بك يا مدير النظام", 20f)

        addButton("إدارة السائقين وطلبات التسجيل") {
            showPendingDrivers()
        }

        addButton("إدارة الركاب") {
            showPassengers()
        }

        addButton("متابعة الرحلات") {
            showTrips()
        }

        addButton("التقارير والإحصائيات") {
            showReports()
        }

        val logout = Button(this).apply {
            text = "تسجيل الخروج"
        }

        main.addView(logout)

        logout.setOnClickListener {
            auth.signOut()
            checkingAccount = false
            selectedAccountType = "auto"
            showRoleSelectionScreen()
        }

        setContentView(scroll)
    }

    // =========================
    // عرض طلبات السائقين
    // =========================

    private fun showPendingDrivers() {
        val progress = AlertDialog.Builder(this)
            .setTitle("طلبات السائقين")
            .setMessage("جارٍ تحميل الطلبات...")
            .setCancelable(false)
            .create()

        progress.show()

        db.collection("drivers")
            .whereEqualTo("status", "pending")
            .get()
            .addOnSuccessListener { result ->
                progress.dismiss()

                if (result.isEmpty) {
                    AlertDialog.Builder(this)
                        .setTitle("طلبات السائقين")
                        .setMessage("لا توجد طلبات تسجيل معلقة حاليًا.")
                        .setPositiveButton("موافق", null)
                        .show()
                    return@addOnSuccessListener
                }

                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 10, 20, 10)
                }

                result.documents.forEach { document ->

                    val driverName =
                        document.getString("name") ?: "بدون اسم"

                    val driverPhone =
                        document.getString("phone") ?: "غير مسجل"

                    val vehicle =
                        document.getString("vehicleType") ?: "غير محدد"

                    val plate =
                        document.getString("plateNumber") ?: "غير محدد"

                    val info = TextView(this).apply {
                        text =
                            "اسم السائق: $driverName\n" +
                            "الهاتف: $driverPhone\n" +
                            "نوع المركبة: $vehicle\n" +
                            "رقم اللوحة: $plate"

                        textSize = 17f
                        setTextColor(Color.BLACK)
                        setPadding(10, 15, 10, 15)
                    }

                    container.addView(info)

                    val buttons = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }

                    val approve = Button(this).apply {
                        text = "موافقة"
                    }

                    val reject = Button(this).apply {
                        text = "رفض"
                    }

                    buttons.addView(
                        approve,
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    )

                    buttons.addView(
                        reject,
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    )

                    container.addView(buttons)

                    val divider = TextView(this).apply {
                        text = "ــــــــــــــــــــــــــــ"
                        gravity = Gravity.CENTER
                        setTextColor(Color.GRAY)
                    }

                    container.addView(divider)

                    approve.setOnClickListener {
                        confirmDriverDecision(
                            document.id,
                            "approved",
                            "الموافقة على السائق"
                        )
                    }

                    reject.setOnClickListener {
                        confirmDriverDecision(
                            document.id,
                            "rejected",
                            "رفض السائق"
                        )
                    }
                }

                val scroll = ScrollView(this)
                scroll.addView(container)

                AlertDialog.Builder(this)
                    .setTitle("طلبات السائقين المعلقة")
                    .setView(scroll)
                    .setPositiveButton("إغلاق", null)
                    .show()
            }
            .addOnFailureListener { error ->
                progress.dismiss()
                saveError("Loading drivers failed", error)
                showError("تعذر تحميل طلبات السائقين: ${error.message}")
            }
    }

    // =========================
    // الموافقة أو الرفض
    // =========================

    private fun confirmDriverDecision(
        driverId: String,
        newStatus: String,
        title: String
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("هل أنت متأكد من تنفيذ هذا القرار؟")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("تأكيد") { _, _ ->

                db.collection("drivers")
                    .document(driverId)
                    .update("status", newStatus)
                    .addOnSuccessListener {
                        showError(
                            if (newStatus == "approved")
                                "تمت الموافقة على السائق"
                            else
                                "تم رفض طلب السائق"
                        )

                        showPendingDrivers()
                    }
                    .addOnFailureListener { error ->
                        saveError("Driver decision failed", error)
                        showError("تعذر تنفيذ القرار: ${error.message}")
                    }
            }
            .show()
    }

    // نهاية الجزء الثاني
        // =========================
    // عرض الركاب
    // =========================

    private fun showPassengers() {
        db.collection("users")
            .get()
            .addOnSuccessListener { result ->

                val message = StringBuilder()

                if (result.isEmpty) {
                    message.append("لا يوجد ركاب مسجلون")
                } else {
                    for (document in result) {
                        val phone = document.getString("phone") ?: "غير متاح"
                        message.append("رقم الهاتف: $phone\n")
                        message.append("رقم الحساب: ${document.id}\n")
                        message.append("----------------------\n")
                    }
                }

                AlertDialog.Builder(this)
                    .setTitle("الركاب المسجلون")
                    .setMessage(message.toString())
                    .setPositiveButton("رجوع") { _, _ ->
                        showAdminScreen()
                    }
                    .show()
            }
            .addOnFailureListener { error ->
                saveError("Loading passengers failed", error)
                showError("تعذر تحميل الركاب: ${error.message}")
            }
    }

    // =========================
    // عرض الرحلات
    // =========================

    private fun showTrips() {
        db.collection("trips")
            .get()
            .addOnSuccessListener { result ->

                if (result.isEmpty) {
                    AlertDialog.Builder(this)
                        .setTitle("الرحلات")
                        .setMessage("لا توجد رحلات مسجلة")
                        .setPositiveButton("رجوع") { _, _ ->
                            showAdminScreen()
                        }
                        .show()
                    return@addOnSuccessListener
                }

                val trips = result.documents
                val items = trips.map { document ->

                    val pickup = document.getString("pickup") ?: "غير محدد"
                    val destination =
                        document.getString("destination") ?: "غير محدد"
                    val status = document.getString("status") ?: "غير محدد"
                    val vehicle =
                        document.getString("vehicleType") ?: "غير محدد"

                    "من: $pickup\nإلى: $destination\nالوسيلة: $vehicle\nالحالة: $status"
                }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle("جميع الرحلات")
                    .setItems(items) { _, which ->
                        val selectedTrip = trips[which]
                        showTripDetails(selectedTrip.id)
                    }
                    .setNegativeButton("رجوع") { _, _ ->
                        showAdminScreen()
                    }
                    .show()
            }
            .addOnFailureListener { error ->
                saveError("Loading trips failed", error)
                showError("تعذر تحميل الرحلات: ${error.message}")
            }
    }

    // =========================
    // تفاصيل الرحلة
    // =========================

    private fun showTripDetails(tripId: String) {
        db.collection("trips")
            .document(tripId)
            .get()
            .addOnSuccessListener { document ->

                if (!document.exists()) {
                    showError("الرحلة غير موجودة")
                    return@addOnSuccessListener
                }

                val pickup = document.getString("pickup") ?: ""
                val destination = document.getString("destination") ?: ""
                val status = document.getString("status") ?: ""
                val vehicle = document.getString("vehicleType") ?: ""
                val passengerPhone =
                    document.getString("passengerPhone") ?: "غير متاح"
                val driverUid =
                    document.getString("driverUid") ?: "لم يتم تعيين سائق"

                val details =
                    "رقم الرحلة:\n$tripId\n\n" +
                    "الراكب: $passengerPhone\n\n" +
                    "وسيلة المواصلات: $vehicle\n\n" +
                    "مكان الركوب: $pickup\n\n" +
                    "الوجهة: $destination\n\n" +
                    "الحالة: $status\n\n" +
                    "السائق: $driverUid"

                AlertDialog.Builder(this)
                    .setTitle("تفاصيل الرحلة")
                    .setMessage(details)
                    .setPositiveButton("تغيير الحالة") { _, _ ->
                        updateTripStatus(tripId)
                    }
                    .setNeutralButton("تعيين سائق") { _, _ ->
                        assignDriverToTrip(tripId)
                    }
                    .setNegativeButton("رجوع") { _, _ ->
                        showTrips()
                    }
                    .show()
            }
            .addOnFailureListener { error ->
                saveError("Trip details failed", error)
                showError("تعذر عرض تفاصيل الرحلة")
            }
    }

    // =========================
    // تعيين سائق للرحلة
    // =========================

    private fun assignDriverToTrip(tripId: String) {

        db.collection("drivers")
            .whereEqualTo("status", "approved")
            .get()
            .addOnSuccessListener { result ->

                if (result.isEmpty) {
                    showError("لا يوجد سائقون مقبولون حاليًا")
                    return@addOnSuccessListener
                }

                val drivers = result.documents

                val driverNames = drivers.map { driver ->
                    val name = driver.getString("name") ?: "سائق"
                    val phone = driver.getString("phone") ?: ""
                    "$name - $phone"
                }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle("اختيار السائق")
                    .setItems(driverNames) { _, position ->

                        val driver = drivers[position]

                        db.collection("trips")
                            .document(tripId)
                            .update(
                                mapOf(
                                    "driverUid" to driver.id,
                                    "status" to "assigned"
                                )
                            )
                            .addOnSuccessListener {
                                showError("تم تعيين السائق بنجاح")
                                showTripDetails(tripId)
                            }
                            .addOnFailureListener { error ->
                                saveError("Assign driver failed", error)
                                showError("تعذر تعيين السائق")
                            }
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
            .addOnFailureListener { error ->
                saveError("Loading approved drivers failed", error)
                showError("تعذر تحميل السائقين")
            }
    }

    // =========================
    // تغيير حالة الرحلة
    // =========================

    private fun updateTripStatus(tripId: String) {

        val statuses = arrayOf(
            "في انتظار سائق",
            "تم تعيين سائق",
            "السائق في الطريق",
            "بدأت الرحلة",
            "انتهت الرحلة",
            "تم إلغاء الرحلة"
        )

        val values = arrayOf(
            "requested",
            "assigned",
            "driver_on_way",
            "started",
            "completed",
            "cancelled"
        )

        AlertDialog.Builder(this)
            .setTitle("اختار حالة الرحلة")
            .setItems(statuses) { _, position ->

                val newStatus = values[position]

                db.collection("trips")
                    .document(tripId)
                    .update("status", newStatus)
                    .addOnSuccessListener {
                        showError("تم تحديث حالة الرحلة")
                        showTripDetails(tripId)
                    }
                    .addOnFailureListener { error ->
                        saveError("Update trip status failed", error)
                        showError("تعذر تحديث حالة الرحلة")
                    }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // =========================
    // التقارير
    // =========================

    private fun showReports() {

        db.collection("drivers").get()
            .addOnSuccessListener { drivers ->

                db.collection("users").get()
                    .addOnSuccessListener { users ->

                        db.collection("trips").get()
                            .addOnSuccessListener { trips ->

                                val approvedDrivers =
                                    drivers.documents.count {
                                        it.getString("status") == "approved"
                                    }

                                val pendingDrivers =
                                    drivers.documents.count {
                                        it.getString("status") == "pending"
                                    }

                                val completedTrips =
                                    trips.documents.count {
                                        it.getString("status") == "completed"
                                    }

                                val message =
                                    "تقرير تطبيق مشوارك\n\n" +
                                    "إجمالي السائقين: ${drivers.size()}\n" +
                                    "السائقون المقبولون: $approvedDrivers\n" +
                                    "طلبات السائقين المعلقة: $pendingDrivers\n\n" +
                                    "إجمالي الركاب: ${users.size()}\n" +
                                    "إجمالي الرحلات: ${trips.size()}\n" +
                                    "الرحلات المكتملة: $completedTrips"

                                AlertDialog.Builder(this)
                                    .setTitle("تقارير مشوارك")
                                    .setMessage(message)
                                    .setPositiveButton("رجوع") { _, _ ->
                                        showAdminScreen()
                                    }
                                    .show()
                            }
                            .addOnFailureListener {
                                showError("تعذر تحميل بيانات الرحلات")
                            }
                    }
                    .addOnFailureListener {
                        showError("تعذر تحميل بيانات الركاب")
                    }
            }
            .addOnFailureListener {
                showError("تعذر تحميل بيانات السائقين")
            }
    }

    // =========================
    // صلاحيات الموقع
    // =========================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                findCurrentLocation()
            } else {
                showError("يجب السماح بالوصول إلى الموقع لاستخدام الخريطة")
            }
        }
    }

    // =========================
    // تحديد الموقع الحالي
    // =========================

    private fun findCurrentLocation() {

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }

        try {
            val manager =
                getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val location = manager.getLastKnownLocation(
                LocationManager.GPS_PROVIDER
            ) ?: manager.getLastKnownLocation(
                LocationManager.NETWORK_PROVIDER
            )

            if (location != null) {
                moveMapToLocation(location.latitude, location.longitude)
            } else {
                showError("جارٍ تحديد موقعك، حاول مرة أخرى")
                startLocationSearch()
            }

        } catch (error: Exception) {
            saveError("Getting location failed", error)
            showError("تعذر تحديد الموقع")
        }
    }

    private fun startLocationSearch() {

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            val manager =
                getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    moveMapToLocation(
                        location.latitude,
                        location.longitude
                    )

                    manager.removeUpdates(this)
                    locationListener = null
                }
            }

            locationListener = listener

            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L,
                5f,
                listener,
                Looper.getMainLooper()
            )

        } catch (error: Exception) {
            saveError("Location search failed", error)
        }
    }

    // =========================
    // تحويل الإحداثيات إلى اسم مكان
    // =========================

    private fun getPlaceName(
        latitude: Double,
        longitude: Double
    ): String {

        return try {
            val geocoder = Geocoder(this, Locale("ar", "EG"))
            val addresses = geocoder.getFromLocation(
                latitude,
                longitude,
                1
            )

            if (!addresses.isNullOrEmpty()) {
                addresses[0].getAddressLine(0) ?: "موقع محدد على الخريطة"
            } else {
                "موقع محدد على الخريطة"
            }

        } catch (error: Exception) {
            "موقع محدد على الخريطة"
        }
    }

    // =========================
    // تحريك الخريطة
    // =========================

    private fun moveMapToLocation(
        latitude: Double,
        longitude: Double
    ) {
        mapLibreMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(latitude, longitude),
                15.0
            )
        )
    }

    // =========================
    // إنشاء الخريطة
    // =========================

    private fun createMapView(): MapView {

        if (mapView == null) {
            mapView = MapView(this)
            mapView?.onCreate(null)

            mapView?.getMapAsync { map ->

                mapLibreMap = map

                map.setStyle(
                    Style.Builder().fromUri(
                        "https://demotiles.maplibre.org/style.json"
                    )
                ) {

                    findCurrentLocation()

                    map.addOnMapClickListener { point ->

                        val latitude = point.latitude
                        val longitude = point.longitude

                        val placeName = getPlaceName(
                            latitude,
                            longitude
                        )

                        val targetField = activeMapSelection

                        if (targetField != null) {
                            targetField.setText(placeName)
                            activeMapSelection = null
                        }

                        true
                    }
                }
            }
        }

        return mapView!!
    }

    // =========================
    // شاشة الراكب
    // =========================

    private fun showHomeScreen() {

        val scroll = ScrollView(this)

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.rgb(10, 35, 70))
        }

        scroll.addView(main)

        fun addTitle(
            value: String,
            size: Float,
            color: Int
        ) {
            val title = TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(5, 15, 5, 15)
            }

            main.addView(title)
        }

        fun addField(hintText: String): EditText {
            val field = EditText(this).apply {
                hint = hintText
                textSize = 16f
                setTextColor(Color.BLACK)
                setHintTextColor(Color.GRAY)
                setPadding(15, 10, 15, 10)
                setBackgroundColor(Color.WHITE)
            }

            main.addView(field)
            return field
        }

        addTitle("مشوارك في إيدك", 28f, Color.YELLOW)
        addTitle("اطلب مشوارك بسهولة", 19f, Color.WHITE)

        val transportGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        val vehicles = arrayOf(
            "توك توك",
            "تاكسي",
            "سيارة خاصة",
            "ميكروباص"
        )

        for (vehicle in vehicles) {
            val radio = RadioButton(this).apply {
                text = vehicle
                textSize = 18f
                setTextColor(Color.WHITE)
            }

            transportGroup.addView(radio)
        }

        addTitle("اختار وسيلة المواصلات", 20f, Color.YELLOW)
        main.addView(transportGroup)

        addTitle("مكان الركوب", 20f, Color.YELLOW)

        val pickup = addField("مكان ركوبك")

        val selectPickup = Button(this).apply {
            text = "📍 اختيار مكان الركوب من الخريطة"
        }

        main.addView(selectPickup)

        selectPickup.setOnClickListener {
            activeMapSelection = pickup
            showError("اضغط على المكان المطلوب في الخريطة")
        }

        addTitle("خريطة مشوارك", 22f, Color.YELLOW)

        val mapContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val mapParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            500
        )

        mapParams.setMargins(0, 10, 0, 5)

        try {
            mapContainer.addView(
                createMapView(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )

            main.addView(mapContainer, mapParams)

            val attribution = TextView(this).apply {
                text = "© OpenStreetMap contributors"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(5, 5, 5, 12)
            }

            main.addView(attribution)

        } catch (error: Exception) {
            saveError("Map creation failed", error)
            showError("تعذر إنشاء الخريطة: ${error.message}")
        }

        addTitle("الوجهة المطلوبة", 20f, Color.YELLOW)

        val destination = addField(
            "اكتب المكان الذي تريد الذهاب إليه"
        )

        val selectDestination = Button(this).apply {
            text = "🎯 اختيار الوجهة من الخريطة"
        }

        main.addView(selectDestination)

        selectDestination.setOnClickListener {
            activeMapSelection = destination
            showError("اضغط على الوجهة المطلوبة في الخريطة")
        }

        val request = Button(this).apply {
            text = "اطلب مشوار"
            textSize = 20f
            setTextColor(Color.rgb(10, 35, 70))
            setBackgroundColor(Color.YELLOW)
            setPadding(15, 15, 15, 15)
        }

        main.addView(request)

        request.setOnClickListener {

            val from = pickup.text.toString().trim()
            val to = destination.text.toString().trim()

            if (transportGroup.checkedRadioButtonId == -1) {
                showError("اختار وسيلة المواصلات")
                return@setOnClickListener
            }

            if (from.isEmpty() || to.isEmpty()) {
                showError("اكتب مكان الركوب والوجهة")
                return@setOnClickListener
            }

            val currentUser = auth.currentUser

            if (currentUser == null) {
                showError("سجل دخولك مرة أخرى")
                showRoleSelectionScreen()
                return@setOnClickListener
            }

            val selected = findViewById<RadioButton>(
                transportGroup.checkedRadioButtonId
            ).text.toString()

            request.isEnabled = false
            request.text = "جارٍ إرسال الطلب..."

            val tripData = hashMapOf<String, Any>(
                "passengerUid" to currentUser.uid,
                "passengerPhone" to (currentUser.phoneNumber ?: ""),
                "vehicleType" to selected,
                "pickup" to from,
                "destination" to to,
                "status" to "requested",
                "createdAt" to FieldValue.serverTimestamp()
            )

            db.collection("trips")
                .add(tripData)
                .addOnSuccessListener { document ->

                    request.isEnabled = true
                    request.text = "اطلب مشوار"

                    AlertDialog.Builder(this)
                        .setTitle("تم إرسال طلبك")
                        .setMessage(
                            "تم تسجيل طلب الرحلة بنجاح.\n\n" +
                            "رقم الطلب: ${document.id}\n\n" +
                            "وسيلة المواصلات: $selected\n\n" +
                            "مكان الركوب: $from\n\n" +
                            "الوجهة: $to\n\n" +
                            "الحالة: في انتظار سائق"
                        )
                        .setPositiveButton("موافق", null)
                        .show()
                }
                .addOnFailureListener { error ->

                    request.isEnabled = true
                    request.text = "اطلب مشوار"

                    saveError("Creating trip failed", error)

                    showError(
                        "تعذر إرسال الطلب: ${error.message}"
                    )
                }
        }

        val driverRegister = Button(this).apply {
            text = "🚕 التسجيل كسائق في مشوارك"
            textSize = 18f
        }

        main.addView(driverRegister)

        driverRegister.setOnClickListener {
            selectedAccountType = "driver"
            showDriverRegistrationScreen()
        }

        val logout = Button(this).apply {
            text = "تسجيل الخروج"
        }

        main.addView(logout)

        logout.setOnClickListener {
            auth.signOut()
            checkingAccount = false
            selectedAccountType = "auto"
            showRoleSelectionScreen()
        }

        setContentView(scroll)
    }

    // =========================
    // دورة حياة الخريطة
    // =========================

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapView?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        locationHandler.removeCallbacksAndMessages(null)

        locationListener?.let { listener ->
            try {
                val manager =
                    getSystemService(Context.LOCATION_SERVICE) as LocationManager

                manager.removeUpdates(listener)

            } catch (error: Exception) {
                Log.e("MISHWARK", "Removing location listener failed", error)
            }
        }

        mapView?.onDestroy()
        mapView = null
        mapLibreMap = null

        super.onDestroy()
    }
}
    
