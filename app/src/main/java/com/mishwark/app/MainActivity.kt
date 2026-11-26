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

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source

import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.CustomCredential
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException

import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

import okhttp3.OkHttpClient

import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.camera.CameraUpdateFactory


class MainActivity : Activity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private var checkingAccount = false

    private var selectedAccountType = "auto"

    private val LOCATION_PERMISSION_CODE = 200

    private var pendingPickup: EditText? = null

    private var locationListener: LocationListener? = null

    private val locationHandler =
        Handler(Looper.getMainLooper())

    private var mapView: MapView? = null

    private var mapLibreMap: MapLibreMap? = null

    private var activeMapSelection: EditText? = null

    private lateinit var credentialManager: CredentialManager

    private val adminUid =
        "b7Qe8kkkf1U0cb2AV3xBz76k6Vv1"


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


    // =========================
    // تسجيل الأخطاء
    // =========================

    private fun saveError(
        message: String,
        error: Throwable? = null
    ) {

        try {

            val details = buildString {

                append("\n--- MISHWARK ERROR ---\n")
                append(message)
                append("\n")
                append(error?.stackTraceToString() ?: "")
            }

            openFileOutput(
                "mishwark_errors.txt",
                Context.MODE_APPEND
            )
                .bufferedWriter()
                .use {
                    it.write(details)
                }

            Log.e(
                "MISHWARK_ERROR",
                message,
                error
            )

        } catch (e: Exception) {

            Log.e(
                "MISHWARK_ERROR",
                "Could not save error",
                e
            )
        }
    }


    // =========================
    // تشغيل التطبيق
    // =========================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        val oldHandler =
            Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler {
                thread, error ->

            saveError(
                "APP CRASHED",
                error
            )

            oldHandler?.uncaughtException(
                thread,
                error
            )
        }

        try {

            val file =
                getFileStreamPath("mishwark_errors.txt")

            if (file.exists()) {

                val oldErrors = file.readText()

                AlertDialog.Builder(this)
                    .setTitle("تقرير أخطاء مشوارك")
                    .setMessage(oldErrors.takeLast(3000))
                    .setPositiveButton("موافق", null)
                    .show()
            }

        } catch (e: Exception) {

            Log.e(
                "MISHWARK_ERROR",
                "Reading error file failed",
                e
            )
        }

        try {

            MapLibre.getInstance(this)

            val mapClient =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->

                        val request =
                            chain.request()
                                .newBuilder()
                                .header(
                                    "User-Agent",
                                    "Mishwark/1.0 Android"
                                )
                                .build()

                        chain.proceed(request)
                    }
                    .build()

            HttpRequestUtil.setOkHttpClient(mapClient)

            FirebaseApp.initializeApp(this)
                ?: throw IllegalStateException(
                    "Firebase initialization failed"
                )

            auth = FirebaseAuth.getInstance()
            db = FirebaseFirestore.getInstance()

            credentialManager =
                CredentialManager.create(this)

            showCorrectScreen()

        } catch (e: Exception) {

            saveError(
                "Application startup failed",
                e
            )

            showError(
                "خطأ في تشغيل التطبيق: ${e.message}"
            )
        }
    }


    // =========================
    // رسالة للمستخدم
    // =========================

    private fun showError(
        message: String
    ) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }


    // =========================
    // تحديد الشاشة حسب الحساب
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

        db.collection("drivers")
            .document(user.uid)
            .get(Source.SERVER)
            .addOnSuccessListener { driverDocument ->

                if (driverDocument.exists()) {

                    checkingAccount = false

                    val status =
                        driverDocument.getString("status")
                            ?: "pending"

                    showDriverScreen(status)

                } else {

                    checkPassengerAccount(user)
                }
            }
            .addOnFailureListener { error ->

                checkingAccount = false

                saveError(
                    "Checking driver account failed",
                    error
                )

                showError(
                    "تعذر الاتصال بقاعدة البيانات. " +
                    "تأكد من الإنترنت وحاول مرة أخرى."
                )
            }
    }


    // =========================
    // التحقق من حساب السائق
    // =========================

    private fun checkDriverAccount(
        user: FirebaseUser
    ) {

        db.collection("drivers")
            .document(user.uid)
            .get(Source.SERVER)
            .addOnSuccessListener { document ->

                checkingAccount = false

                if (document.exists()) {

                    val status =
                        document.getString("status")
                            ?: "pending"

                    showDriverScreen(status)

                } else {

                    showDriverRegistrationScreen()
                }
            }.addOnFailureListener { error ->
    checkingAccount = false

    saveError("Checking driver account failed", error)

    val errorCode = (error as? FirebaseFirestoreException)?.code
    val errorMessage = error.message ?: "لا توجد تفاصيل"

    showError(
        "خطأ التحقق من حساب السائق\n\n" +
        "كود الخطأ: $errorCode\n\n" +
        "التفاصيل: $errorMessage"
    )
}
    }


    // =========================
    // التحقق من حساب الراكب
    // =========================

    private fun checkPassengerAccount(
        user: FirebaseUser
    ) {

        db.collection("users")
            .document(user.uid)
            .get(Source.SERVER)
            .addOnSuccessListener { passengerDocument ->

                if (passengerDocument.exists()) {

                    val role =
                        passengerDocument.getString("role")

                    if (
                        role != null &&
                        role != "passenger"
                    ) {

                        checkingAccount = false

                        showError(
                            "نوع الحساب غير معروف، " +
                            "يرجى مراجعة الإدارة"
                        )

                        return@addOnSuccessListener
                    }

                    val status =
                        passengerDocument.getString("status")
                            ?: "active"

                    if (status == "suspended") {

                        checkingAccount = false

                        AlertDialog.Builder(this)
                            .setTitle("الحساب موقوف")
                            .setMessage(
                                "تم إيقاف حسابك. " +
                                "يرجى التواصل مع الإدارة."
                            )
                            .setCancelable(false)
                            .setPositiveButton(
                                "تسجيل الخروج"
                            ) { _, _ ->

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

                    val passengerData =
                        hashMapOf<String, Any>(

                            "uid" to user.uid,

                            "email" to (
                                user.email ?: ""
                            ),

                            "name" to (
                                user.displayName ?: ""
                            ),

                            "phone" to (
                                user.phoneNumber ?: ""
                            ),

                            "role" to "passenger",

                            "status" to "active",

                            "createdAt" to
                                FieldValue.serverTimestamp()
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

                            saveError(
                                "Passenger creation failed",
                                error
                            )

                            showError(
                                "تعذر إنشاء حساب الراكب: " +
                                "${error.message}"
                            )
                        }
                }
            }.addOnFailureListener { error ->

    checkingAccount = false

    saveError(
        "Checking passenger account failed",
        error
    )

    val errorCode =
        (error as? FirebaseFirestoreException)?.code

    val errorMessage =
        error.message ?: "لا توجد تفاصيل"

    AlertDialog.Builder(this)
        .setTitle("خطأ التحقق من حساب الراكب")
        .setMessage(
            "كود الخطأ: $errorCode\n\n" +
            "التفاصيل:\n$errorMessage"
        )
        .setPositiveButton("موافق", null)
        .show()
}
    }

    // =========================
    // إنشاء تصميم الشاشة
    // =========================

    private fun createLayout(): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            setPadding(
                30,
                30,
                30,
                30
            )

            setBackgroundColor(
                Color.rgb(10, 35, 70)
            )
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
            showGoogleLoginScreen()
        }

        passengerButton.setOnClickListener {
            selectedAccountType = "passenger"
            showGoogleLoginScreen()
        }
    }


    // =========================
    // شاشة تسجيل Google
    // =========================

    private fun showGoogleLoginScreen() {

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

            text = if (selectedAccountType == "driver") {
                "تسجيل حساب سائق"
            } else {
                "تسجيل حساب راكب"
            }

            textSize = 20f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        val googleButton = Button(this).apply {
            text = "تسجيل الدخول باستخدام Google"
            textSize = 18f
            setTextColor(Color.rgb(10, 35, 70))
            setBackgroundColor(Color.WHITE)
            setPadding(15, 20, 15, 20)
        }

        val back = Button(this).apply {
            text = "العودة لاختيار نوع الحساب"
        }

        layout.addView(title)
        layout.addView(slogan)
        layout.addView(accountType)
        layout.addView(googleButton)
        layout.addView(back)

        setContentView(layout)

        googleButton.setOnClickListener {
            startGoogleSignIn(googleButton)
        }

        back.setOnClickListener {
            selectedAccountType = "auto"
            showRoleSelectionScreen()
        }
    }
        // =========================
    // تسجيل الدخول باستخدام Google
    // =========================

    private fun startGoogleSignIn(
        googleButton: Button
    ) {

        googleButton.isEnabled = false
        googleButton.text = "جارٍ فتح حسابات Google..."

        try {

            val googleOption =
                GetGoogleIdOption.Builder()
                    .setServerClientId(
                        getString(R.string.default_web_client_id)
                    )
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()

            val request =
                GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build()

            credentialManager.getCredentialAsync(
                this,
                request,
                null,
                mainExecutor,
                object : CredentialManagerCallback<
                    GetCredentialResponse,
                    GetCredentialException
                > {

                    override fun onResult(
                        result: GetCredentialResponse
                    ) {

                        handleGoogleCredential(
                            result,
                            googleButton
                        )
                    }

                    override fun onError(
                        error: GetCredentialException
                    ) {

                        saveError(
                            "Google credential request failed",
                            error
                        )

                        if (error is NoCredentialException) {

                            startGoogleSignInFallback(
                                googleButton
                            )

                        } else {

                            googleButton.isEnabled = true

                            googleButton.text =
                                "تسجيل الدخول باستخدام Google"

                            showError(
                                "تعذر فتح حسابات Google: " +
                                "${error.message}"
                            )
                        }
                    }
                }
            )

        } catch (error: Exception) {

            googleButton.isEnabled = true

            googleButton.text =
                "تسجيل الدخول باستخدام Google"

            saveError(
                "Starting Google sign-in failed",
                error
            )

            showError(
                "حدث خطأ أثناء تسجيل الدخول: " +
                "${error.message}"
            )
        }
    }


    // =========================
    // الطريقة البديلة لتسجيل Google
    // =========================

    private fun startGoogleSignInFallback(
        googleButton: Button
    ) {

        googleButton.isEnabled = false
        googleButton.text = "جارٍ فتح تسجيل Google..."

        try {

            val googleOption =
                GetSignInWithGoogleOption.Builder(
                    getString(R.string.default_web_client_id)
                )
                    .build()

            val request =
                GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build()

            credentialManager.getCredentialAsync(
                this,
                request,
                null,
                mainExecutor,
                object : CredentialManagerCallback<
                    GetCredentialResponse,
                    GetCredentialException
                > {

                    override fun onResult(
                        result: GetCredentialResponse
                    ) {

                        handleGoogleCredential(
                            result,
                            googleButton
                        )
                    }

                    override fun onError(
                        error: GetCredentialException
                    ) {

                        googleButton.isEnabled = true

                        googleButton.text =
                            "تسجيل الدخول باستخدام Google"

                        saveError(
                            "Google fallback sign-in failed",
                            error
                        )

                        showError(
                            "تعذر تسجيل الدخول بجوجل: " +
                            "${error.message}"
                        )
                    }
                }
            )

        } catch (error: Exception) {

            googleButton.isEnabled = true

            googleButton.text =
                "تسجيل الدخول باستخدام Google"

            saveError(
                "Google fallback exception",
                error
            )

            showError(
                "حدث خطأ في الطريقة البديلة: " +
                "${error.message}"
            )
        }
    }


    // =========================
    // معالجة بيانات حساب Google
    // =========================

    private fun handleGoogleCredential(
        result: GetCredentialResponse,
        googleButton: Button
    ) {

        try {

            val credential =
                result.credential

            if (
                credential !is CustomCredential ||
                credential.type !=
                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {

                googleButton.isEnabled = true

                googleButton.text =
                    "تسجيل الدخول باستخدام Google"

                showError(
                    "بيانات حساب Google غير صحيحة"
                )

                return
            }

            val googleId =
                GoogleIdTokenCredential.createFrom(
                    credential.data
                )

            val firebaseCredential =
                GoogleAuthProvider.getCredential(
                    googleId.idToken,
                    null
                )

            googleButton.isEnabled = false

            googleButton.text =
                "جارٍ تسجيل الدخول..."

            auth.signInWithCredential(
                firebaseCredential
            )
                .addOnCompleteListener(this) { task ->

                    googleButton.isEnabled = true

                    googleButton.text =
                        "تسجيل الدخول باستخدام Google"

                    if (task.isSuccessful) {

                        val user =
                            auth.currentUser

                        if (user == null) {

                            showError(
                                "تعذر قراءة حساب Google"
                            )

                            return@addOnCompleteListener
                        }

                        checkingAccount = false

                        showError(
                            "تم تسجيل الدخول بنجاح"
                        )

                        when (selectedAccountType) {

                            "passenger" -> {
                                checkPassengerAccount(user)
                            }

                            "driver" -> {
                                checkDriverAccount(user)
                            }

                            else -> {
                                showCorrectScreen()
                            }
                        }

                    } else {

                        saveError(
                            "Google Firebase sign-in failed",
                            task.exception
                        )

                        showError(
                            "فشل تسجيل الدخول: " +
                            "${task.exception?.message}"
                        )
                    }
                }

        } catch (error: Exception) {

            googleButton.isEnabled = true

            googleButton.text =
                "تسجيل الدخول باستخدام Google"

            saveError(
                "Reading Google credential failed",
                error
            )

            showError(
                "تعذر قراءة حساب Google: " +
                "${error.message}"
            )
        }
    }


    // =========================
    // إضافة حساب من الإدارة
    // =========================

    private fun addAccountDialog() {

        if (!isAdminUser()) {
            showError("غير مسموح لك بإضافة حسابات")
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(35, 20, 35, 10)
        }

        val emailInput = EditText(this).apply {
            hint = "البريد الإلكتروني Gmail"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            textSize = 18f
            setSingleLine(true)
        }

        val title = TextView(this).apply {
            text = "اختر نوع الحساب"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 20, 0, 10)
        }

        val accountGroup =
            RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
            }

        val passengerRadio =
            RadioButton(this).apply {
                text = "راكب"
                textSize = 18f
                id = 7101
            }

        val driverRadio =
            RadioButton(this).apply {
                text = "سائق"
                textSize = 18f
                id = 7102
            }

        accountGroup.addView(passengerRadio)
        accountGroup.addView(driverRadio)

        passengerRadio.isChecked = true

        layout.addView(emailInput)
        layout.addView(title)
        layout.addView(accountGroup)

        val dialog = AlertDialog.Builder(this)
            .setTitle("إضافة حساب جديد")
            .setView(layout)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("إضافة", null)
            .create()

        dialog.setOnShowListener {

            dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener {

                val email =
                    emailInput.text.toString()
                        .trim()
                        .lowercase(Locale.ROOT)

                if (
                    !android.util.Patterns.EMAIL_ADDRESS
                        .matcher(email)
                        .matches()
                ) {

                    showError(
                        "أدخل بريد إلكتروني صحيح"
                    )

                    return@setOnClickListener
                }

                val role =
                    if (driverRadio.isChecked) {
                        "driver"
                    } else {
                        "passenger"
                    }

                if (!isAdminUser()) {

                    showError(
                        "انتهت صلاحية الإدارة"
                    )

                    dialog.dismiss()
                    return@setOnClickListener
                }

                val button =
                    dialog.getButton(
                        AlertDialog.BUTTON_POSITIVE
                    )

                button.isEnabled = false
                button.text = "جارٍ الحفظ..."

                val accountData =
                    hashMapOf<String, Any>(
                        "email" to email,
                        "role" to role,
                        "status" to "invited",
                        "createdAt" to
                            FieldValue.serverTimestamp(),
                        "createdBy" to adminUid
                    )

                db.collection("account_registry")
                    .document(email)
                    .get(Source.SERVER)
                    .addOnSuccessListener { existing ->

                        if (existing.exists()) {

                            button.isEnabled = true
                            button.text = "إضافة"

                            showError(
                                "هذا البريد مضاف بالفعل"
                            )

                        } else {

                            db.collection("account_registry")
                                .document(email)
                                .set(accountData)
                                .addOnSuccessListener {

                                    dialog.dismiss()

                                    val accountName =
                                        if (role == "driver") {
                                            "سائق"
                                        } else {
                                            "راكب"
                                        }

                                    AlertDialog.Builder(this)
                                        .setTitle(
                                            "تمت الإضافة بنجاح"
                                        )
                                        .setMessage(
                                            "تم تسجيل دعوة الحساب.\n\n" +
                                            "البريد: $email\n" +
                                            "نوع الحساب: $accountName\n\n" +
                                            "صاحب البريد يسجل الدخول باستخدام Google."
                                        )
                                        .setPositiveButton(
                                            "موافق",
                                            null
                                        )
                                        .show()
                                }
                                .addOnFailureListener { error ->

                                    button.isEnabled = true
                                    button.text = "إضافة"

                                    saveError(
                                        "Adding account failed",
                                        error
                                    )

                                    showError(
                                        "فشل إضافة الحساب:\n" +
                                        "${error.message}"
                                    )
                                }
                        }
                    }
                    .addOnFailureListener { error ->

                        button.isEnabled = true
                        button.text = "إضافة"

                        saveError(
                            "Checking registered account failed",
                            error
                        )

                        showError(
                            "تعذر التحقق من البريد:\n" +
                            "${error.message}"
                        )
                    }
            }
        }

        dialog.show()
    }
        // =========================
    // تسجيل السائق
    // =========================

    private fun showDriverRegistrationScreen() {

        val user = auth.currentUser ?: return

        val main = createLayout()

        val scroll = ScrollView(this)

        scroll.addView(main)

        fun addText(
            text: String,
            size: Float,
            color: Int
        ) {

            val view = TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(10, 15, 10, 15)
            }

            main.addView(view)
        }

        fun addInput(
            hintText: String
        ): EditText {

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

        addText(
            "مشوارك",
            35f,
            Color.YELLOW
        )

        addText(
            "تسجيل بيانات السائق",
            24f,
            Color.WHITE
        )

        val name =
            addInput("اسم السائق بالكامل")

        val email =
            addInput("البريد الإلكتروني")

        email.setText(
            user.email ?: ""
        )

        val phone =
            addInput("رقم الهاتف (اختياري)")

        addText(
            "اختر نوع المركبة",
            18f,
            Color.YELLOW
        )

        val vehicleGroup =
            RadioGroup(this).apply {
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

        val plate =
            addInput("رقم لوحة المركبة")

        val submit = Button(this).apply {
            text = "إرسال طلب التسجيل"
            textSize = 18f
            setBackgroundColor(Color.YELLOW)
            setTextColor(Color.rgb(10, 35, 70))
        }

        main.addView(submit)

        submit.setOnClickListener {

            val driverName =
                name.text.toString().trim()

            val driverEmail =
                email.text.toString().trim()

            val driverPhone =
                phone.text.toString().trim()

            val plateNumber =
                plate.text.toString().trim()

            if (driverName.isEmpty()) {
                showError("اكتب اسم السائق")
                return@setOnClickListener
            }

            if (driverEmail.isEmpty()) {
                showError("البريد الإلكتروني مطلوب")
                return@setOnClickListener
            }

            if (
                vehicleGroup.checkedRadioButtonId == -1
            ) {
                showError("اختر نوع المركبة")
                return@setOnClickListener
            }

            if (plateNumber.isEmpty()) {
                showError("اكتب رقم لوحة المركبة")
                return@setOnClickListener
            }

            val selectedVehicle =
                findViewById<RadioButton>(
                    vehicleGroup.checkedRadioButtonId
                ).text.toString()

            submit.isEnabled = false
            submit.text = "جارٍ إرسال الطلب..."

            val driverData =
                hashMapOf<String, Any>(
                    "uid" to user.uid,
                    "name" to driverName,
                    "email" to driverEmail,
                    "phone" to driverPhone,
                    "vehicleType" to selectedVehicle,
                    "plateNumber" to plateNumber,
                    "status" to "pending",
                    "createdAt" to
                        FieldValue.serverTimestamp()
                )

            db.collection("drivers")
                .document(user.uid)
                .set(driverData)
                .addOnSuccessListener {

                    showError(
                        "تم إرسال طلبك بنجاح"
                    )

                    showDriverScreen("pending")
                }
                .addOnFailureListener { error ->

                    submit.isEnabled = true
                    submit.text = "إرسال طلب التسجيل"

                    saveError(
                        "Driver registration failed",
                        error
                    )

                    showError(
                        "فشل إرسال الطلب: ${error.message}"
                    )
                }
        }

        val back = Button(this).apply {
            text = "تسجيل الخروج"
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

    private fun showDriverScreen(
        status: String
    ) {

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

            "approved" ->
                "تم قبول طلبك! يمكنك العمل كسائق."

            "rejected" ->
                "نأسف، تم رفض طلب التسجيل."

            else ->
                "طلب التسجيل قيد المراجعة من الإدارة."
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

            val approvedInfo =
                TextView(this).apply {
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

            val uid =
                auth.currentUser?.uid
                    ?: return@setOnClickListener

            refresh.isEnabled = false

            db.collection("drivers")
                .document(uid)
                .get(Source.SERVER)
                .addOnSuccessListener { document ->

                    refresh.isEnabled = true

                    if (document.exists()) {

                        showDriverScreen(
                            document.getString("status")
                                ?: "pending"
                        )

                    } else {

                        selectedAccountType = "auto"
                        showRoleSelectionScreen()
                    }
                }
                .addOnFailureListener { error ->

                    refresh.isEnabled = true

                    saveError(
                        "Refreshing driver status failed",
                        error
                    )

                    showError(
                        "تعذر تحديث حالة الطلب"
                    )
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
    // لوحة تحكم الإدارة
    // =========================

    private fun showAdminScreen() {

        if (!isAdminUser()) {
            showError("غير مسموح بالدخول")
            return
        }

        val main = createLayout()
        val scroll = ScrollView(this)

        scroll.addView(main)

        val title = TextView(this).apply {
            text = "لوحة تحكم إدارة مشوارك"
            textSize = 25f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 25)
        }

        main.addView(title)

        fun addButton(
            text: String,
            action: () -> Unit
        ) {

            val button = Button(this).apply {
                this.text = text
                textSize = 18f
                setTextColor(Color.rgb(10, 35, 70))
                setBackgroundColor(Color.YELLOW)
                setPadding(10, 12, 10, 12)
            }

            main.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
            )

            button.setOnClickListener {
                action()
            }
        }

        addButton("➕ إضافة حساب جديد") {
            addAccountDialog()
        }

        addButton("🚕 طلبات تسجيل السائقين") {
            showPendingDrivers()
        }

        addButton("👥 إدارة الركاب") {
            showPassengers()
        }

        addButton("🚗 إدارة الرحلات") {
            showTrips()
        }

        addButton("📊 التقارير والإحصائيات") {
            showReports()
        }

        addButton("🔄 تحديث لوحة الإدارة") {
            showAdminScreen()
        }

        addButton("تسجيل الخروج") {

            auth.signOut()
            checkingAccount = false
            selectedAccountType = "auto"

            showRoleSelectionScreen()
        }

        setContentView(scroll)
    }


    // =========================
    // التحقق من المدير
    // =========================

    private fun isAdminUser(): Boolean {

        return auth.currentUser?.uid == adminUid
    }


    // =========================
    // طلبات تسجيل السائقين
    // =========================

    private fun showPendingDrivers() {

        if (!isAdminUser()) {
            showError("غير مسموح بالدخول")
            return
        }

        val main = createLayout()
        val scroll = ScrollView(this)

        scroll.addView(main)

        val title = TextView(this).apply {
            text = "طلبات تسجيل السائقين"
            textSize = 24f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        main.addView(title)

        val back = Button(this).apply {
            text = "العودة للإدارة"
        }

        main.addView(back)

        back.setOnClickListener {
            showAdminScreen()
        }

        setContentView(scroll)

        db.collection("drivers")
            .whereEqualTo("status", "pending")
            .get(Source.SERVER)
            .addOnSuccessListener { result ->

                if (result.isEmpty) {

                    val empty = TextView(this).apply {
                        text = "لا توجد طلبات سائقين جديدة"
                        textSize = 18f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setPadding(10, 30, 10, 30)
                    }

                    main.addView(empty)

                    return@addOnSuccessListener
                }

                for (document in result) {

                    val name =
                        document.getString("name")
                            ?: "بدون اسم"

                    val email =
                        document.getString("email")
                            ?: "غير متوفر"

                    val phone =
                        document.getString("phone")
                            ?: "غير متوفر"

                    val vehicle =
                        document.getString("vehicleType")
                            ?: ""

                    val plate =
                        document.getString("plateNumber")
                            ?: ""

                    val card = TextView(this).apply {

                        text =
                            "الاسم: $name\n" +
                            "البريد الإلكتروني: $email\n" +
                            "الهاتف: $phone\n" +
                            "المركبة: $vehicle\n" +
                            "رقم اللوحة: $plate"

                        textSize = 17f
                        setTextColor(Color.WHITE)
                        setPadding(15, 20, 15, 20)

                        setBackgroundColor(
                            Color.rgb(25, 55, 90)
                        )
                    }

                    main.addView(card)

                    val accept = Button(this).apply {
                        text = "قبول السائق"
                    }

                    main.addView(accept)

                    accept.setOnClickListener {
                        confirmDriverDecision(
                            document.id,
                            true
                        )
                    }

                    val reject = Button(this).apply {
                        text = "رفض الطلب"
                    }

                    main.addView(reject)

                    reject.setOnClickListener {
                        confirmDriverDecision(
                            document.id,
                            false
                        )
                    }
                }
            }
            .addOnFailureListener { error ->

                saveError(
                    "Loading pending drivers failed",
                    error
                )

                showError(
                    "فشل تحميل طلبات السائقين: " +
                    "${error.message}"
                )
            }
    }


    // =========================
    // قبول أو رفض السائق
    // =========================

    private fun confirmDriverDecision(
        driverId: String,
        accepted: Boolean
    ) {

        if (!isAdminUser()) {
            showError("غير مسموح بتنفيذ العملية")
            return
        }

        val newStatus =
            if (accepted) "approved" else "rejected"

        AlertDialog.Builder(this)
            .setTitle("تأكيد القرار")
            .setMessage(
                if (accepted)
                    "هل تريد قبول السائق؟"
                else
                    "هل تريد رفض طلب السائق؟"
            )
            .setPositiveButton("تأكيد") { _, _ ->

                db.collection("drivers")
                    .document(driverId)
                    .update(
                        "status",
                        newStatus
                    )
                    .addOnSuccessListener {

                        showError(
                            "تم تنفيذ القرار بنجاح"
                        )

                        showPendingDrivers()
                    }
                    .addOnFailureListener { error ->

                        saveError(
                            "Driver decision failed",
                            error
                        )

                        showError(
                            "فشل تنفيذ القرار: " +
                            "${error.message}"
                        )
                    }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
        // =========================
    // إدارة الركاب
    // =========================

    private fun showPassengers() {

        if (!isAdminUser()) {
            showError("غير مسموح بالدخول")
            return
        }

        val main = createLayout()
        val scroll = ScrollView(this)

        scroll.addView(main)

        val title = TextView(this).apply {
            text = "إدارة الركاب"
            textSize = 24f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        main.addView(title)

        val back = Button(this).apply {
            text = "العودة للإدارة"
        }

        main.addView(back)

        back.setOnClickListener {
            showAdminScreen()
        }

        setContentView(scroll)

        db.collection("users")
            .whereEqualTo("role", "passenger")
            .get(Source.SERVER)
            .addOnSuccessListener { result ->

                if (result.isEmpty) {

                    val empty = TextView(this).apply {
                        text = "لا يوجد ركاب مسجلون"
                        textSize = 18f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setPadding(10, 30, 10, 30)
                    }

                    main.addView(empty)

                    return@addOnSuccessListener
                }

                for (document in result) {

                    val name =
                        document.getString("name")
                            ?: "بدون اسم"

                    val email =
                        document.getString("email")
                            ?: "غير متوفر"

                    val phone =
                        document.getString("phone")
                            ?: "غير متوفر"

                    val status =
                        document.getString("status")
                            ?: "غير محدد"

                    val item = TextView(this).apply {

                        text =
                            "الاسم: $name\n" +
                            "البريد الإلكتروني: $email\n" +
                            "الهاتف: $phone\n" +
                            "الحالة: $status"

                        textSize = 17f
                        setTextColor(Color.WHITE)
                        setPadding(15, 20, 15, 20)

                        setBackgroundColor(
                            Color.rgb(25, 55, 90)
                        )
                    }

                    main.addView(item)
                }
            }
            .addOnFailureListener { error ->

                saveError(
                    "Loading passengers failed",
                    error
                )

                showError(
                    "فشل تحميل الركاب: " +
                    "${error.message}"
                )
            }
    }


    // =========================
    // إدارة الرحلات
    // =========================

    private fun showTrips() {

        if (!isAdminUser()) {
            showError("غير مسموح بالدخول")
            return
        }

        val main = createLayout()
        val scroll = ScrollView(this)

        scroll.addView(main)

        val title = TextView(this).apply {
            text = "إدارة الرحلات"
            textSize = 24f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        main.addView(title)

        val back = Button(this).apply {
            text = "العودة للإدارة"
        }

        main.addView(back)

        back.setOnClickListener {
            showAdminScreen()
        }

        setContentView(scroll)

        db.collection("trips")
            .get(Source.SERVER)
            .addOnSuccessListener { result ->

                if (result.isEmpty) {

                    val empty = TextView(this).apply {
                        text = "لا توجد رحلات حالياً"
                        textSize = 18f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setPadding(10, 30, 10, 30)
                    }

                    main.addView(empty)

                    return@addOnSuccessListener
                }

                for (document in result) {

                    val status =
                        document.getString("status")
                            ?: "غير محدد"

                    val passenger =
                        document.getString("passengerEmail")
                            ?: document.getString("passengerPhone")
                            ?: document.getString("phone")
                            ?: "غير متوفر"

                    val pickup =
                        document.getString("pickup")
                            ?: document.getString("from")
                            ?: "غير محدد"

                    val destination =
                        document.getString("destination")
                            ?: document.getString("to")
                            ?: "غير محدد"

                    val item = TextView(this).apply {

                        text =
                            "رقم الرحلة: ${document.id}\n" +
                            "الراكب: $passenger\n" +
                            "من: $pickup\n" +
                            "إلى: $destination\n" +
                            "الحالة: $status"

                        textSize = 16f
                        setTextColor(Color.WHITE)
                        setPadding(15, 20, 15, 20)

                        setBackgroundColor(
                            Color.rgb(25, 55, 90)
                        )
                    }

                    main.addView(item)

                    val detailsButton = Button(this).apply {
                        text = "تفاصيل الرحلة"
                    }

                    main.addView(detailsButton)

                    detailsButton.setOnClickListener {
                        showTripDetails(document.id)
                    }

                    val assignButton = Button(this).apply {
                        text = "تعيين سائق"
                    }

                    main.addView(assignButton)

                    assignButton.setOnClickListener {
                        assignDriverToTrip(document.id)
                    }

                    val finishButton = Button(this).apply {
                        text = "إنهاء الرحلة"
                    }

                    main.addView(finishButton)

                    finishButton.setOnClickListener {
                        updateTripStatus(
                            document.id,
                            "completed"
                        )
                    }

                    val cancelButton = Button(this).apply {
                        text = "إلغاء الرحلة"
                    }

                    main.addView(cancelButton)

                    cancelButton.setOnClickListener {
                        updateTripStatus(
                            document.id,
                            "cancelled"
                        )
                    }
                }
            }
            .addOnFailureListener { error ->

                saveError(
                    "Loading trips failed",
                    error
                )

                showError(
                    "فشل تحميل الرحلات: " +
                    "${error.message}"
                )
            }
    }


    // =========================
    // تفاصيل الرحلة
    // =========================

    private fun showTripDetails(
        tripId: String
    ) {

        if (!isAdminUser()) {
            showError("غير مسموح بالدخول")
            return
        }

        db.collection("trips")
            .document(tripId)
            .get(Source.SERVER)
            .addOnSuccessListener { document ->

                if (!document.exists()) {
                    showError("الرحلة غير موجودة")
                    return@addOnSuccessListener
                }

                val details =
                    document.data
                        ?.entries
                        ?.joinToString("\n") { entry ->
                            "${entry.key}: ${entry.value}"
                        }
                        ?: "لا توجد تفاصيل"

                AlertDialog.Builder(this)
                    .setTitle("تفاصيل الرحلة")
                    .setMessage(details)
                    .setPositiveButton("موافق", null)
                    .show()
            }
            .addOnFailureListener { error ->

                saveError(
                    "Loading trip details failed",
                    error
                )

                showError(
                    "تعذر تحميل التفاصيل: " +
                    "${error.message}"
                )
            }
    }


    // =========================
    // تعيين سائق للرحلة
    // =========================

    private fun assignDriverToTrip(
        tripId: String
    ) {

        if (!isAdminUser()) {
            showError("غير مسموح بالدخول")
            return
        }

        db.collection("drivers")
            .whereEqualTo("status", "approved")
            .get(Source.SERVER)
            .addOnSuccessListener { result ->

                if (result.isEmpty) {

                    showError(
                        "لا يوجد سائقون مقبولون حالياً"
                    )

                    return@addOnSuccessListener
                }

                val drivers = result.documents

                val names = drivers.map { driver ->

                    val name =
                        driver.getString("name")
                            ?: "بدون اسم"

                    val phone =
                        driver.getString("phone")
                            ?: ""

                    "$name - $phone"

                }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle("اختر السائق")
                    .setItems(names) { _, which ->

                        val driver = drivers[which]

                        val data =
                            hashMapOf<String, Any>(

                                "driverId" to driver.id,

                                "driverName" to (
                                    driver.getString("name") ?: ""
                                ),

                                "driverPhone" to (
                                    driver.getString("phone") ?: ""
                                ),

                                "driverEmail" to (
                                    driver.getString("email") ?: ""
                                ),

                                "status" to "accepted",

                                "assignedAt" to
                                    FieldValue.serverTimestamp()
                            )

                        db.collection("trips")
                            .document(tripId)
                            .update(data)
                            .addOnSuccessListener {

                                showError(
                                    "تم تعيين السائق بنجاح"
                                )

                                showTrips()
                            }
                            .addOnFailureListener { error ->

                                saveError(
                                    "Assigning driver failed",
                                    error
                                )

                                showError(
                                    "فشل تعيين السائق: " +
                                    "${error.message}"
                                )
                            }
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
            .addOnFailureListener { error ->

                saveError(
                    "Loading drivers failed",
                    error
                )

                showError(
                    "تعذر تحميل السائقين: " +
                    "${error.message}"
                )
            }
    }


    // =========================
    // تحديث حالة الرحلة
    // =========================

    private fun updateTripStatus(
        tripId: String,
        status: String
    ) {

        if (!isAdminUser()) {
            showError("غير مسموح بتنفيذ العملية")
            return
        }

        val message = when (status) {
            "completed" -> "هل تريد إنهاء الرحلة؟"
            "cancelled" -> "هل تريد إلغاء الرحلة؟"
            else -> "هل تريد تحديث حالة الرحلة؟"
        }

        AlertDialog.Builder(this)
            .setTitle("تأكيد العملية")
            .setMessage(message)
            .setPositiveButton("تأكيد") { _, _ ->

                db.collection("trips")
                    .document(tripId)
                    .update(
                        mapOf(
                            "status" to status,
                            "updatedAt" to
                                FieldValue.serverTimestamp()
                        )
                    )
                    .addOnSuccessListener {

                        showError(
                            "تم تحديث حالة الرحلة"
                        )

                        showTrips()
                    }
                    .addOnFailureListener { error ->

                        saveError(
                            "Updating trip status failed",
                            error
                        )

                        showError(
                            "فشل تحديث الرحلة: " +
                            "${error.message}"
                        )
                    }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }


    // =========================
    // التقارير والإحصائيات
    // =========================

    private fun showReports() {

        if (!isAdminUser()) {
            showError("غير مسموح بالدخول")
            return
        }

        val main = createLayout()
        val scroll = ScrollView(this)

        scroll.addView(main)

        val title = TextView(this).apply {
            text = "تقارير وإحصائيات مشوارك"
            textSize = 24f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }

        main.addView(title)

        val reportText = TextView(this).apply {
            text = "جارٍ تحميل الإحصائيات..."
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(15, 25, 15, 25)
        }

        main.addView(reportText)

        val back = Button(this).apply {
            text = "العودة للإدارة"
        }

        main.addView(back)

        back.setOnClickListener {
            showAdminScreen()
        }

        setContentView(scroll)

        var passengersCount = 0
        var driversCount = 0
        var tripsCount = 0
        var pendingCount = 0

        var completedRequests = 0
        var failedRequests = 0

        fun showFinalReport() {

            if (completedRequests < 3) {
                return
            }

            reportText.text =
                "إحصائيات التطبيق\n\n" +
                "عدد الركاب: $passengersCount\n\n" +
                "عدد السائقين: $driversCount\n\n" +
                "عدد الرحلات: $tripsCount\n\n" +
                "طلبات السائقين المعلقة: $pendingCount\n\n" +
                "الاستعلامات التي فشلت: $failedRequests"
        }

        db.collection("users")
            .whereEqualTo("role", "passenger")
            .get(Source.SERVER)
            .addOnSuccessListener {

                passengersCount = it.size()

                completedRequests++

                showFinalReport()
            }
            .addOnFailureListener {

                failedRequests++
                completedRequests++

                showFinalReport()
            }

        db.collection("drivers")
            .get(Source.SERVER)
            .addOnSuccessListener {

                driversCount = it.size()

                pendingCount =
                    it.documents.count { document ->
                        document.getString("status") == "pending"
                    }

                completedRequests++

                showFinalReport()
            }
            .addOnFailureListener {

                failedRequests++
                completedRequests++

                showFinalReport()
            }

        db.collection("trips")
            .get(Source.SERVER)
            .addOnSuccessListener {

                tripsCount = it.size()

                completedRequests++

                showFinalReport()
            }
            .addOnFailureListener {

                failedRequests++
                completedRequests++

                showFinalReport()
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

        if (requestCode == LOCATION_PERMISSION_CODE) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] ==
                PackageManager.PERMISSION_GRANTED
            ) {

                findCurrentLocation()

            } else {

                showError(
                    "يجب السماح بالوصول إلى الموقع لاستخدام الخريطة"
                )
            }
        }
    }


    // =========================
    // تحديد الموقع الحالي
    // =========================

    private fun findCurrentLocation() {

        if (
            checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_CODE
            )

            return
        }

        try {

            val manager =
                getSystemService(
                    Context.LOCATION_SERVICE
                ) as LocationManager

            val location =
                manager.getLastKnownLocation(
                    LocationManager.GPS_PROVIDER
                )
                    ?: manager.getLastKnownLocation(
                        LocationManager.NETWORK_PROVIDER
                    )

            if (location != null) {

                moveMapToLocation(
                    location.latitude,
                    location.longitude
                )

            } else {

                showError(
                    "جارٍ تحديد موقعك، حاول مرة أخرى"
                )

                startLocationSearch()
            }

        } catch (error: Exception) {

            saveError(
                "Getting location failed",
                error
            )

            showError(
                "تعذر تحديد موقعك"
            )
        }
    }


    // =========================
    // البحث عن الموقع
    // =========================

    private fun startLocationSearch() {

        if (
            checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {

            val manager =
                getSystemService(
                    Context.LOCATION_SERVICE
                ) as LocationManager

            val listener =
                object : LocationListener {

                    override fun onLocationChanged(
                        location: Location
                    ) {

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

            saveError(
                "Location search failed",
                error
            )
        }
    }


    // =========================
    // تحويل الإحداثيات لاسم مكان
    // =========================

    private fun getPlaceName(
        latitude: Double,
        longitude: Double
    ): String {

        return try {

            val geocoder =
                Geocoder(
                    this,
                    Locale("ar", "EG")
                )

            val addresses =
                geocoder.getFromLocation(
                    latitude,
                    longitude,
                    1
                )

            if (!addresses.isNullOrEmpty()) {

                addresses[0].getAddressLine(0)
                    ?: "موقع محدد على الخريطة"

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
                LatLng(
                    latitude,
                    longitude
                ),
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

                        val latitude =
                            point.latitude

                        val longitude =
                            point.longitude

                        val placeName =
                            getPlaceName(
                                latitude,
                                longitude
                            )

                        val targetField =
                            activeMapSelection

                        if (targetField != null) {

                            targetField.setText(
                                placeName
                            )

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

            setPadding(
                20,
                20,
                20,
                20
            )

            setBackgroundColor(
                Color.rgb(10, 35, 70)
            )
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

                setPadding(
                    5,
                    15,
                    5,
                    15
                )
            }

            main.addView(title)
        }

        fun addField(
            hintText: String
        ): EditText {

            val field = EditText(this).apply {

                hint = hintText

                textSize = 16f

                setTextColor(Color.BLACK)

                setHintTextColor(Color.GRAY)

                setPadding(
                    15,
                    10,
                    15,
                    10
                )

                setBackgroundColor(Color.WHITE)
            }

            main.addView(field)

            return field
        }

        addTitle(
            "مشوارك في إيدك",
            28f,
            Color.YELLOW
        )

        addTitle(
            "اطلب مشوارك بسهولة",
            19f,
            Color.WHITE
        )

        val currentUser =
            auth.currentUser

        if (currentUser != null) {

            addTitle(
                "مرحباً ${currentUser.displayName ?: "بك"}",
                18f,
                Color.WHITE
            )

            addTitle(
                currentUser.email ?: "",
                15f,
                Color.WHITE
            )
        }

        val transportGroup =
            RadioGroup(this).apply {
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

        addTitle(
            "اختار وسيلة المواصلات",
            20f,
            Color.YELLOW
        )

        main.addView(transportGroup)

        addTitle(
            "مكان الركوب",
            20f,
            Color.YELLOW
        )

        val pickup =
            addField("مكان ركوبك")

        val selectPickup = Button(this).apply {

            text =
                "📍 اختيار مكان الركوب من الخريطة"
        }

        main.addView(selectPickup)

        selectPickup.setOnClickListener {

            activeMapSelection = pickup

            showError(
                "اضغط على المكان المطلوب في الخريطة"
            )
        }

        addTitle(
            "خريطة مشوارك",
            22f,
            Color.YELLOW
        )

        val mapContainer =
            LinearLayout(this).apply {

                orientation = LinearLayout.VERTICAL

                setBackgroundColor(Color.WHITE)
            }

        val mapParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                500
            )

        mapParams.setMargins(
            0,
            10,
            0,
            5
        )

        try {

            mapContainer.addView(
                createMapView(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )

            main.addView(
                mapContainer,
                mapParams
            )

            val attribution = TextView(this).apply {

                text =
                    "© OpenStreetMap contributors"

                textSize = 12f

                setTextColor(Color.WHITE)

                gravity = Gravity.CENTER

                setPadding(
                    5,
                    5,
                    5,
                    12
                )
            }

            main.addView(attribution)

        } catch (error: Exception) {

            saveError(
                "Map creation failed",
                error
            )

            showError(
                "تعذر إنشاء الخريطة: ${error.message}"
            )
        }

        addTitle(
            "الوجهة المطلوبة",
            20f,
            Color.YELLOW
        )

        val destination =
            addField(
                "اكتب المكان الذي تريد الذهاب إليه"
            )

        val selectDestination =
            Button(this).apply {

                text =
                    "🎯 اختيار الوجهة من الخريطة"
            }

        main.addView(selectDestination)

        selectDestination.setOnClickListener {

            activeMapSelection = destination

            showError(
                "اضغط على الوجهة المطلوبة في الخريطة"
            )
        }

        val request = Button(this).apply {

            text = "اطلب مشوار"

            textSize = 20f

            setTextColor(
                Color.rgb(10, 35, 70)
            )

            setBackgroundColor(Color.YELLOW)

            setPadding(
                15,
                15,
                15,
                15
            )
        }

        main.addView(request)

        request.setOnClickListener {

            val from =
                pickup.text.toString().trim()

            val to =
                destination.text.toString().trim()

            if (
                transportGroup.checkedRadioButtonId == -1
            ) {

                showError(
                    "اختار وسيلة المواصلات"
                )

                return@setOnClickListener
            }

            if (
                from.isEmpty() ||
                to.isEmpty()
            ) {

                showError(
                    "اكتب مكان الركوب والوجهة"
                )

                return@setOnClickListener
            }

            val user =
                auth.currentUser

            if (user == null) {

                showError(
                    "سجل دخولك مرة أخرى"
                )

                showRoleSelectionScreen()

                return@setOnClickListener
            }

            val selected =
                findViewById<RadioButton>(
                    transportGroup.checkedRadioButtonId
                ).text.toString()

            request.isEnabled = false

            request.text =
                "جارٍ إرسال الطلب..."

            val tripData =
                hashMapOf<String, Any>(

                    "passengerUid" to
                        user.uid,

                    "passengerEmail" to
                        (user.email ?: ""),

                    "passengerName" to
                        (user.displayName ?: ""),

                    "passengerPhone" to
                        (user.phoneNumber ?: ""),

                    "vehicleType" to selected,

                    "pickup" to from,

                    "destination" to to,

                    "status" to "requested",

                    "createdAt" to
                        FieldValue.serverTimestamp()
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
                        .setPositiveButton(
                            "موافق",
                            null
                        )
                        .show()
                }
                .addOnFailureListener { error ->

                    request.isEnabled = true

                    request.text = "اطلب مشوار"

                    saveError(
                        "Creating trip failed",
                        error
                    )

                    showError(
                        "تعذر إرسال الطلب: " +
                        "${error.message}"
                    )
                }
        }

        val driverRegister =
            Button(this).apply {

                text =
                    "🚕 التسجيل كسائق في مشوارك"

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

    override fun onSaveInstanceState(
        outState: Bundle
    ) {

        mapView?.onSaveInstanceState(
            outState
        )

        super.onSaveInstanceState(
            outState
        )
    }

    override fun onDestroy() {

        locationHandler.removeCallbacksAndMessages(
            null
        )

        locationListener?.let { listener ->

            try {

                val manager =
                    getSystemService(
                        Context.LOCATION_SERVICE
                    ) as LocationManager

                manager.removeUpdates(listener)

            } catch (error: Exception) {

                Log.e(
                    "MISHWARK",
                    "Removing location listener failed",
                    error
                )
            }
        }

        mapView?.onDestroy()

        mapView = null

        mapLibreMap = null

        super.onDestroy()
    }

}
