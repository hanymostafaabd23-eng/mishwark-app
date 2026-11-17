
package com.mishwark.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.text.InputType
import android.util.Log
import android.widget.*
import android.content.Context
import android.app.AlertDialog

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.PhoneAuthCredential

import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var auth: FirebaseAuth
    private var verificationId: String? = null

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
            FirebaseApp.initializeApp(this)
                ?: throw IllegalStateException("Firebase initialization failed")

            auth = FirebaseAuth.getInstance()
            showPhoneScreen()

        } catch (e: Exception) {
            saveError("Application startup failed", e)
            showError("خطأ في تشغيل التطبيق: ${e.message}")
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun createLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.rgb(10, 35, 70))
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
            saveError("Starting phone verification")

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
                        showError("تم تسجيل الدخول بنجاح في مشوارك")
                    } else {
                        saveError("Sign in failed", task.exception)
                        showError("فشل تسجيل الدخول: ${task.exception?.message}")
                    }
                }

        } catch (e: Exception) {
            saveError("Sign in crashed", e)
            showError("خطأ في تسجيل الدخول: ${e.message}")
        }
    }
}
