package com.mishwark.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class MainActivity : Activity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private var verificationId: String? = null
    private var checkingAccount = false
    private var selectedAccountType = "auto"

    private val locationHandler = Handler(Looper.getMainLooper())

    private lateinit var credentialManager: CredentialManager

    private var googleCredentialPending: AuthCredential? = null
    private var googleEmailPending: String? = null

    private val adminUid = "b7Qe8kkkf1U0cb2AV3xBz76k6Vv1"

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
            ).bufferedWriter().use {
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

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
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
            Log.e(
                "MISHWARK_ERROR",
                "Reading error file failed",
                e
            )
        }

        try {
            MapLibre.getInstance(this)

            val mapClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
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
            credentialManager = CredentialManager.create(this)

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

    private fun showError(
        message: String
    ) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

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
            .get(Source.DEFAULT)
            .addOnSuccessListener { driverDocument ->
                if (driverDocument.exists()) {
                    checkingAccount = false

                    val status = driverDocument.getString("status")
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
                    "تعذر الاتصال بقاعدة البيانات: ${error.localizedMessage ?: error.message ?: "تأكد من الإنترنت وحاول مرة أخرى"}"
                )
            }
    }

    private fun checkDriverAccount(
        user: FirebaseUser
    ) {
        db.collection("drivers")
            .document(user.uid)
            .get(Source.DEFAULT)
            .addOnSuccessListener { document ->
                checkingAccount = false

                if (document.exists()) {
                    val status = document.getString("status")
                        ?: "pending"

                    showDriverScreen(status)
                } else {
                    showDriverRegistrationScreen()
                }
            }
            .addOnFailureListener { error ->
                checkingAccount = false

                saveError(
                    "Checking driver account failed",
                    error
                )

                showError(
                    "تعذر التحقق من حساب السائق: ${error.localizedMessage ?: error.message ?: "راجع اتصال الإنترنت وحاول مرة أخرى"}"
                )
            }
    }

    private fun checkPassengerAccount(
        user: FirebaseUser
    ) {
        db.collection("users")
            .document(user.uid)
            .get(Source.DEFAULT)
            .addOnSuccessListener { passengerDocument ->
                if (passengerDocument.exists()) {
                    val role = passengerDocument.getString("role")

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

                    val status = passengerDocument.getString("status")
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
            }
            .addOnFailureListener { error ->
                checkingAccount = false

                saveError(
                    "Checking passenger account failed",
                    error
                )

                showError(
                    "تعذر الاتصال بقاعدة البيانات: ${error.localizedMessage ?: error.message ?: "لم يتم إنشاء حساب جديد"}"
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

        val googleButton = Button(this).apply {
            text = "الدخول أو الربط باستخدام Google"
            textSize = 17f
            setTextColor(Color.rgb(10, 35, 70))
            setBackgroundColor(Color.WHITE)
        }

        val back = Button(this).apply {
            text = "العودة لاختيار نوع الحساب"
        }

        layout.addView(title)
        layout.addView(slogan)
        layout.addView(accountType)
        layout.addView(phone)
        layout.addView(send)
        layout.addView(googleButton)
        layout.addView(back)

        setContentView(layout)

        send.setOnClickListener {
            val number = phone.text.toString().trim()

            if (!number.matches(Regex("^01[0125][0-9]{8}$"))) {
                showError("أدخل رقم مصري صحيح")
                return@setOnClickListener
            }

            sendCode(
                "+20" + number.drop(1),
                send
            )
        }

        googleButton.setOnClickListener {
            startGoogleSignIn(googleButton)
        }

        back.setOnClickListener {
            selectedAccountType = "auto"
            googleCredentialPending = null
            googleEmailPending = null
            showRoleSelectionScreen()
        }
    }

    private fun startGoogleSignIn(
        googleButton: Button
    ) {
        googleButton.isEnabled = false
        googleButton.text = "جارٍ فتح حسابات Google..."

        try {
            val googleOption = GetGoogleIdOption.Builder()
                .setServerClientId(
                    getString(R.string.default_web_client_id)
                )
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
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
                        googleButton.isEnabled = true
                        googleButton.text = "الدخول أو الربط باستخدام Google"

                        try {
                            val credential = result.credential

                            if (
                                credential is CustomCredential &&
                                credential.type ==
                                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                            ) {
                                val googleId = GoogleIdTokenCredential.createFrom(
                                    credential.data
                                )

                                val firebaseCredential = GoogleAuthProvider.getCredential(
                                    googleId.idToken,
                                    null
                                )

                                googleCredentialPending = firebaseCredential
                                googleEmailPending = googleId.id

                                showError(
                                    "تم اختيار حساب Google. " +
                                        "أدخل رقم هاتفك المسجل لدى الإدارة " +
                                        "وأكّد كود SMS لربط الحساب."
                                )
                            } else {
                                showError(
                                    "نوع حساب Google غير مدعوم"
                                )
                            }
                        } catch (error: Exception) {
                            saveError(
                                "Reading Google credential failed",
                                error
                            )

                            showError(
                                "تعذر قراءة بيانات Google: " +
                                    "${error.message}"
                            )
                        }
                    }

                    override fun onError(
                        error: GetCredentialException
                    ) {
                        googleButton.isEnabled = true
                        googleButton.text = "الدخول أو الربط باستخدام Google"

                        saveError(
                            "Google credential request failed",
                            error
                        )

                        showError(
                            "تعذر اختيار حساب Google: " +
                                "${error.message}"
                        )
                    }
                }
            )
        } catch (error: Exception) {
            googleButton.isEnabled = true
            googleButton.text = "الدخول أو الربط باستخدام Google"

            saveError(
                "Starting Google sign-in failed",
                error
            )

            showError(
                "
