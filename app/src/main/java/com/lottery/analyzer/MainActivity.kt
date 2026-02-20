package com.lottery.analyzer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest

class MainActivity : AppCompatActivity() {

    private lateinit var inputNumbers: EditText
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var infoText: TextView
    private var selectedNumbers: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputNumbers = findViewById(R.id.inputNumbers)
        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        infoText = findViewById(R.id.infoText)

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        inputNumbers.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateStatus()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        scanButton.setOnClickListener { validateAndScan() }
        updateInfoText()
    }

    private fun updateStatus() {
        val input = inputNumbers.text.toString().trim()
        
        if (input.isEmpty()) {
            statusText.text = "Введите 15 чисел через запятую или пробел"
            statusText.setTextColor(ContextCompat.getColor(this, R.color.text_gray))
            scanButton.isEnabled = false
            return
        }

        selectedNumbers = parseNumbers(input)
        val count = selectedNumbers.size

        when {
            count == 15 -> {
                statusText.text = "✓ Введено 15 чисел. Готово к сканированию"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
                scanButton.isEnabled = true
            }
            count < 15 -> {
                val needed = 15 - count
                statusText.text = "Нужно внести еще $needed чисел"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.orange))
                scanButton.isEnabled = false
            }
            count > 15 -> {
                val excess = count - 15
                statusText.text = "Нужно убрать $excess чисел"
                statusText.setTextColor(ContextCompat.getColor(this, R.color.red))
                scanButton.isEnabled = false
            }
        }
    }

    private fun updateInfoText() {
        infoText.text = """
            📋 СТРУКТУРА БИЛЕТА "РУССКОЕ ЛОТО":
            
            🔹 БЛОК 1 (ВЕРХНИЙ): 15 чисел
               • 3 строки × 9 ячеек
               • В каждой строке 5 заполненных чисел
            
            🔹 БЛОК 2 (НИЖНИЙ): 15 чисел
               • 3 строки × 9 ячеек
               • В каждой строке 5 заполненных чисел
            
            📱 РЕЗУЛЬТАТЫ:
            🟢 Зелёная = 15 совпадений (выигрыш!)
            🟡 Жёлтая = 13-14 совпадений
            🔴 Красная = менее 13 совпадений
        """.trimIndent()
    }

    private fun parseNumbers(input: String): List<Int> {
        return input.split("[,\\s]+".toRegex()).filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }.filter { it in 1..90 }.distinct().sorted()
    }

    private fun validateAndScan() {
        if (selectedNumbers.size != 15) {
            Toast.makeText(this, "Должно быть ровно 15 чисел", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCameraActivity()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    private fun startCameraActivity() {
        val intent = Intent(this, CameraActivity::class.java)
        intent.putIntegerArrayListExtra("selectedNumbers", ArrayList(selectedNumbers))
        startActivity(intent)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)
            val permissionsToRequest = permissions.filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }.toTypedArray()
            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSIONS_REQUEST_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Разрешения предоставлены", Toast.LENGTH_SHORT).show()
                }
            }
            CAMERA_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    startCameraActivity()
                }
            }
        }
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 100
        private const val CAMERA_PERMISSION_REQUEST = 101
    }
}
