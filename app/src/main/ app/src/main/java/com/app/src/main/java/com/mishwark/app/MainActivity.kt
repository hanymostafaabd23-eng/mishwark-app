
package com.mishwark.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.text.InputType
import android.util.Log
import android.widget.*
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val app = FirebaseApp.initializeApp(this)

            if (app == null) {
                Toast.makeText(
                    this,
                    "تعذر تشغيل Firebase. راجع إعدادات التطبيق",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            auth = FirebaseAuth.getInstance()
            showPhoneScreen()

        } catch (e: Exception) {
            Log.e("MISHWARK_ERROR", "Firebase initialization error", e)
            Toast.makeText(
                this,
                "خطأ في تشغيل التطبيق: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
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
                Toast.makeText(
                    this,
                    "أدخل رقم مصري صحيح",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val fullNumber = "+20" + number.drop(1)
            send.isEnabled = false
            sendCode(fullNumber, send)
        }
    }

    private fun sendCode(number: String, send: Button) {
        try {
            Log.d("MISHWARK_ERROR", "Starting phone verification")

            val callbacks =
                object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

                    override fun onVerificationCompleted(
                        credential: PhoneAuthCredential
                    ) {
                        Log.d("MISHWARK_ERROR", "Verification completed")
                        signIn(credential)
                    }

                    override fun onVerificationFailed(e: FirebaseException) {
                        Log.e(
                            "MISHWARK_ERROR",
                            "Phone verification failed",
                            e
                        )

                        send.isEnabled = true

                        Toast.makeText(
                            this@MainActivity,
                            "فشل إرسال الكود: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    override fun onCodeSent(
                        id: String,
                        token: PhoneAuthProvider.ForceResendingToken
                    ) {
                        Log.d("MISHWARK_ERROR", "SMS code sent")
                        verificationId = id
                        showCodeScreen()
                    }

                    override fun onCodeAutoRetrievalTimeOut(id: String) {
                        Log.d(
                            "MISHWARK_ERROR",
                            "Code auto retrieval timed out"
                        )
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
            Log.e("MISHWARK_ERROR", "Sending code crashed", e)
            send.isEnabled = true

            Toast.makeText(
                this,
                "حدث خطأ: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
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
                Toast.makeText(
                    this,
                    "أدخل كود التأكيد المكون من 6 أرقام",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            verify.isEnabled = false

            val credential =
                PhoneAuthProvider.getCredential(id, enteredCode)

            signIn(credential)
        }
    }

    private fun signIn(credential: PhoneAuthCredential) {
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Toast.makeText(
                        this,
                        "تم تسجيل الدخول بنجاح في مشوارك",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Log.e(
                        "MISHWARK_ERROR",
                        "Sign in failed",
                        task.exception
                    )

                    Toast.makeText(
                        this,
                        "فشل تسجيل الدخول: ${task.exception?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }
}
