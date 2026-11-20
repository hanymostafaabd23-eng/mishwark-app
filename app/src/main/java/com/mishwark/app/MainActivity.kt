
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
    // تحديد الشاشة حسب الحساب
    // =========================

    private fun showCorrectScreen() {

        val user = auth.currentUser

        if (user == null) {
            checkingAccount = false
            showPhoneScreen()
            return
        }

        if (checkingAccount) return

        checkingAccount = true

        // حساب الإدارة
        if (user.uid == adminUid) {
            checkingAccount = false
            showAdminScreen()
            return
        }

        // التحقق من حساب السائق أولاً
        db.collection("drivers")
            .document(user.uid)
            .get()
            .addOnSuccessListener { driverDocument ->

                if (driverDocument.exists()) {

                    checkingAccount = false

                    val status =
                        driverDocument.getString("status") ?: "pending"

                    showDriverScreen(status)
                    return@addOnSuccessListener
                }

                // لا يوجد حساب سائق، نتحقق من حساب الراكب
                checkPassengerAccount(user)
            }
            .addOnFailureListener { error ->

                checkingAccount = false

                saveError("Checking driver account failed", error)

                showError(
                    "تعذر التحقق من حساب السائق. تحقق من الاتصال وحاول مرة أخرى."
                )
            }
    }

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
                                showPhoneScreen()
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

        layout.addView(title)
        layout.addView(slogan)
        layout.addView(phone)
        layout.addView(send)

        setContentView(layout)

        send.setOnClickListener {
            val number = phone.text.toString().trim()

            if (!number.matches(Regex("^01[0125][0-9]{8}$"))) {
                showError("أدخل رقم مصري صحيح")
                return@setOnClickListener
            }

            sendCode("+20" + number.drop(1), send)
        }
    }

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

        layout.addView(title)
        layout.addView(code)
        layout.addView(verify)

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
            text = "العودة"
        }

        main.addView(back)

        back.setOnClickListener {
            showHomeScreen()
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
                        showHomeScreen()
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
            showPhoneScreen()
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
            showError("إدارة الركاب سيتم ربطها في المرحلة التالية")
        }

        addButton("متابعة الرحلات") {
            showError("طلبات الرحلات سيتم ربطها في المرحلة التالية")
        }

        addButton("التقارير والإحصائيات") {
            showError("التقارير سيتم ربطها في المرحلة التالية")
        }

        val logout = Button(this).apply {
            text = "تسجيل الخروج"
        }

        main.addView(logout)

        logout.setOnClickListener {
            auth.signOut()
            checkingAccount = false
            showPhoneScreen()
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

    // =========================
    // الموقع و GPS
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

        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() &&
                grantResults.any {
                    it == PackageManager.PERMISSION_GRANTED
                }
            ) {
                pendingPickup?.let { findCurrentLocation(it) }
            } else {
                showError("يجب السماح بالوصول للموقع أولاً")
            }
        }
    }

    private fun startLocationSearch(pickup: EditText) {
        pendingPickup = pickup

        val fineGranted = checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_CODE
            )
            return
        }

        findCurrentLocation(pickup)
    }

    private fun findCurrentLocation(pickup: EditText) {
        try {
            val manager =
                getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            ).filter { manager.isProviderEnabled(it) }

            if (providers.isEmpty()) {
                showError("شغّل خدمة الموقع GPS من إعدادات الهاتف")
                return
            }

            pickup.setText("جارٍ تحديد موقعك...")

            locationListener?.let {
                manager.removeUpdates(it)
            }

            locationHandler.removeCallbacksAndMessages(null)

            var locationFound = false

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (locationFound) return

                    locationFound = true
                    manager.removeUpdates(this)
                    locationHandler.removeCallbacksAndMessages(null)

                    runOnUiThread {
                        moveMapToLocation(location)
                        getPlaceName(location, pickup)
                    }
                }
            }

            locationListener = listener

            if (checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                showError("اسمح للتطبيق بالوصول إلى الموقع")
                return
            }

            for (provider in providers) {
                manager.requestLocationUpdates(
                    provider,
                    1000L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }

            locationHandler.postDelayed({
                if (!locationFound) {
                    manager.removeUpdates(listener)
                    showError("تعذر تحديد موقعك. تأكد من تشغيل GPS")
                    pickup.setText("")
                }
            }, 20000L)

        } catch (e: Exception) {
            saveError("Location error", e)
            showError("حدث خطأ أثناء تحديد الموقع: ${e.message}")
        }
    }

    private fun getPlaceName(location: Location, pickup: EditText) {
        val latitude = location.latitude
        val longitude = location.longitude

        Thread {
            var place = ""

            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(this, Locale("ar", "EG"))

                    val addresses = geocoder.getFromLocation(
                        latitude,
                        longitude,
                        1
                    )

                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]

                        place = listOfNotNull(
                            address.subLocality,
                            address.locality,
                            address.subAdminArea,
                            address.adminArea
                        )
                            .distinct()
                            .joinToString(" - ")
                    }
                }
            } catch (e: Exception) {
                saveError("Geocoder error", e)
            }

            runOnUiThread {
                if (place.isNotBlank()) {
                    pickup.setText(place)
                    showError("تم تحديد موقعك بنجاح")
                } else {
                    pickup.setText("$latitude, $longitude")
                    showError(
                        "تم تحديد الإحداثيات، لكن اسم المكان غير متاح"
                    )
                }
            }
        }.start()
    }

    private fun moveMapToLocation(location: Location) {
        try {
            val target = LatLng(
                location.latitude,
                location.longitude
            )

            mapLibreMap?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(target, 15.0)
            )
        } catch (e: Exception) {
            saveError("Moving map to location failed", e)
        }
    }

    // =========================
    // الخريطة
    // =========================

    private fun createMapView(): MapView {
        val view = MapView(this)
        mapView = view

        view.onCreate(null)

        view.getMapAsync { map ->
            mapLibreMap = map

            map.setStyle(
                Style.Builder().fromJson(osmStyleJson)
            ) {
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(30.43, 30.96))
                    .zoom(10.0)
                    .build()

                map.addOnMapClickListener { point ->

                    val target = activeMapSelection

                    if (target != null) {
                        target.setText(
                            "${point.latitude}, ${point.longitude}"
                        )

                        activeMapSelection = null
                        showError("تم اختيار الموقع بنجاح")
                    }

                    true
                }
            }
        }

        return view
    }

    // =========================
    // شاشة الراكب
    // =========================

    private fun showHomeScreen() {

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(25, 25, 25, 25)
            setBackgroundColor(Color.rgb(10, 35, 70))
        }

        val scroll = ScrollView(this)
        scroll.addView(main)

        fun addTitle(text: String, size: Float, color: Int) {
            val view = TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(5, 15, 5, 15)
            }

            main.addView(view)
        }

        fun addField(hintText: String): EditText {
            val field = EditText(this).apply {
                hint = hintText
                textSize = 17f
                setTextColor(Color.BLACK)
                setHintTextColor(Color.GRAY)
                setSingleLine(true)
                setPadding(18, 15, 18, 15)
                setBackgroundColor(Color.WHITE)
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            params.setMargins(0, 8, 0, 8)
            main.addView(field, params)

            return field
        }

        addTitle("مشوارك", 38f, Color.YELLOW)
        addTitle("مشوارك في إيدك", 20f, Color.WHITE)
        addTitle("أهلاً بك في مشوارك 🚕", 25f, Color.YELLOW)
        addTitle("اختار وسيلة المواصلات", 21f, Color.WHITE)

        val transportGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(15, 10, 15, 10)
            setBackgroundColor(Color.WHITE)
        }

        val transports = listOf(
            "توك توك",
            "تاكسي",
            "سيارة خاصة",
            "موتوسيكل",
            "ميكروباص"
        )

        transports.forEachIndexed { index, name ->
            val radio = RadioButton(this).apply {
                text = name
                textSize = 18f
                setTextColor(Color.BLACK)
                id = 100 + index
                setPadding(10, 8, 10, 8)
            }

            transportGroup.addView(radio)
        }

        transportGroup.check(100)
        main.addView(transportGroup)

        addTitle("مكان الركوب", 20f, Color.YELLOW)

        val pickup = addField("اكتب مكان انطلاقك")

        val locationButton = Button(this).apply {
            text = "📍 تحديد موقعي الحالي"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(25, 110, 75))
        }

        main.addView(locationButton)

        locationButton.setOnClickListener {
            startLocationSearch(pickup)
        }

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

        } catch (e: Exception) {
            saveError("Map creation failed", e)
            showError("تعذر إنشاء الخريطة: ${e.message}")
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
                showPhoneScreen()
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
            showDriverRegistrationScreen()
        }

        val logout = Button(this).apply {
            text = "تسجيل الخروج"
        }

        main.addView(logout)

        logout.setOnClickListener {
            auth.signOut()
            checkingAccount = false
            showPhoneScreen()
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
            } catch (e: Exception) {
                Log.e("MISHWARK", "Removing location listener failed", e)
            }
        }

        mapView?.onDestroy()
        mapView = null
        mapLibreMap = null

        super.onDestroy()
    }
}
