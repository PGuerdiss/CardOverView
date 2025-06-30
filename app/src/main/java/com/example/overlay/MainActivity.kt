/*
 * File: MainActivity.kt
 * Author: Pedro Henrique Guerdis Silva
 * Email: pedroguerdis@gmail.com
 * GitHub: @PGuerdiss
 *
 * Description: Atividade principal do aplicativo Overlay Counter.
 * Gerencia as permissões, a interface do usuário para adicionar widgets
 * e interage com o FloatingWidgetService.
 *
 * Copyright (c) 2025 Pedro Henrique Guerdis Silva. Todos os direitos reservados.
 */

package com.example.overlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionButton()
    }

    private val galleryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "Permissão da galeria CONCEDIDA.")
            openGallery()
        } else {
            Log.w("MainActivity", "Permissão da galeria NEGADA.")
            Toast.makeText(this, "Permissão da galeria negada.", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            Log.i("MainActivity", "Imagem selecionada com sucesso via OpenDocument. URI: $selectedUri")
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                applicationContext.contentResolver.takePersistableUriPermission(selectedUri, takeFlags)
                Log.i("MainActivity", "Permissão persistente OBTIDA para URI: $selectedUri")
                startFloatingWidgetServiceWithImage(selectedUri)
            } catch (e: SecurityException) {
                Log.e("MainActivity", "SecurityException ao obter permissão persistente para URI: $selectedUri", e)
                Toast.makeText(this, "Falha ao obter acesso persistente à imagem.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Exceção geral ao processar URI da imagem: $selectedUri", e)
                Toast.makeText(this, "Erro ao processar a imagem selecionada.", Toast.LENGTH_LONG).show()
            }
        } ?: run {
            Log.w("MainActivity", "Nenhuma imagem selecionada (URI nulo).")
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val buttonAddImage = findViewById<Button>(R.id.buttonAddImage)
        val buttonRequestPermission = findViewById<Button>(R.id.buttonRequestPermission)
        val buttonAdjustSize = findViewById<Button>(R.id.buttonAdjustSize)
        val buttonSaveLayout = findViewById<Button>(R.id.buttonSaveLayout)
        val buttonLoadLayout = findViewById<Button>(R.id.buttonLoadLayout)
        val buttonHelp = findViewById<ImageButton>(R.id.buttonHelp)

        updatePermissionButton()

        buttonRequestPermission.setOnClickListener {
            requestOverlayPermission()
        }

        buttonAddImage.setOnClickListener {
            checkAndRequestGalleryPermission()
        }

        buttonAdjustSize.setOnClickListener {
            showSizeDialog()
        }

        buttonSaveLayout.setOnClickListener {
            sendActionToService(FloatingWidgetService.ACTION_SAVE_LAYOUT)
            Toast.makeText(this, "Layout Salvo!", Toast.LENGTH_SHORT).show()
        }

        buttonLoadLayout.setOnClickListener {
            sendActionToService(FloatingWidgetService.ACTION_LOAD_LAYOUT)
        }

        buttonHelp.setOnClickListener {
            showHelpDialog()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    private fun sendActionToService(action: String) {
        Intent(this, FloatingWidgetService::class.java).apply {
            this.action = action
            startService(this)
        }
    }

    private fun startFloatingWidgetServiceWithImage(imageUri: Uri) {
        Log.d("MainActivity", "Iniciando FloatingWidgetService com URI: $imageUri")
        Intent(this, FloatingWidgetService::class.java).apply {
            putExtra("imageUri", imageUri.toString())
            startService(this)
        }
    }

    private fun updatePermissionButton() {
        val buttonRequestPermission = findViewById<Button>(R.id.buttonRequestPermission)
        buttonRequestPermission.isEnabled = !checkOverlayPermission()
    }

    private fun checkAndRequestGalleryPermission() {
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        Log.d("MainActivity", "Verificando permissão da galeria: $permissionToRequest")
        when {
            ContextCompat.checkSelfPermission(this, permissionToRequest) == PackageManager.PERMISSION_GRANTED -> {
                Log.d("MainActivity", "Permissão da galeria já concedida. Abrindo seletor de documentos...")
                openGallery()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, permissionToRequest) -> {
                Log.d("MainActivity", "Mostrando explicação para permissão da galeria.")
                showPermissionExplanationDialog(permissionToRequest)
            }
            else -> {
                Log.d("MainActivity", "Solicitando permissão da galeria...")
                galleryPermissionLauncher.launch(permissionToRequest)
            }
        }
    }

    private fun showPermissionExplanationDialog(permission: String) {
        AlertDialog.Builder(this)
            .setTitle("Permissão Necessária")
            .setMessage("Este aplicativo precisa de permissão para visualizar suas imagens na galeria.")
            .setPositiveButton("OK") { _, _ ->
                Log.d("MainActivity", "Usuário clicou OK na explicação da permissão. Solicitando novamente...")
                galleryPermissionLauncher.launch(permission)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openGallery() {
        Log.d("MainActivity", "Abrindo seletor de documentos para imagens...")
        pickImageLauncher.launch(arrayOf("image/*"))
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !checkOverlayPermission()) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
                overlayPermissionLauncher.launch(this)
            }
        }
    }

    private fun showSizeDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_size, null)
        val editTextSizePx = dialogView.findViewById<EditText>(R.id.editTextSizePx)

        AlertDialog.Builder(this)
            .setTitle("Ajustar Tamanho das Imagens (PX)")
            .setView(dialogView)
            .setPositiveButton("Aplicar") { _, _ ->
                val sizeInput = editTextSizePx.text.toString()
                if (sizeInput.isNotEmpty()) {
                    try {
                        val newSizePx = sizeInput.toInt()
                        val adjustSizeIntent = Intent(this, FloatingWidgetService::class.java).apply {
                            action = FloatingWidgetService.ACTION_ADJUST_SIZE
                            putExtra(FloatingWidgetService.EXTRA_NEW_SIZE_PX, newSizePx)
                        }
                        startService(adjustSizeIntent)
                    } catch (e: NumberFormatException) {
                        Log.e("MainActivity", "Entrada de tamanho inválida: '$sizeInput'.", e)
                    }
                } else {
                    Log.w("MainActivity", "Entrada de tamanho vazia.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showHelpDialog() {
        val helpText = """
            Funcionalidades do Aplicativo:

            - Adicionar Imagem: Cria uma nova imagem flutuante na tela.
            
            - Contador na Imagem: Toque em uma imagem para aumentar o número do contador.
            
            - Controles do Contador: Segure o dedo sobre uma imagem para ver os botões de diminuir e zerar.
            
            - Mover e Apagar: Arraste uma imagem para movê-la ou para apagá-la no 'X' que aparece na parte inferior.
            
            - Ajustar Tamanho: Use o botão 'Adjust Size' para definir um novo tamanho (em pixels) para todas as imagens.
            
            - Salvar e Carregar: Use os botões 'Salvar Layout' e 'Carregar Layout' para guardar e restaurar a organização das imagens.

            ---
            Créditos:
            
            Criado por: Pedro Henrique Guerdis Silva
            Email: pedroguerdis@gmail.com
            GitHub: @PGuerdiss
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Ajuda e Créditos")
            .setMessage(helpText)
            .setPositiveButton("Entendi!", null)
            .show()
    }
}
