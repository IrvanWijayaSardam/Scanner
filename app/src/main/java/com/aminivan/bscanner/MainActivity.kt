package com.aminivan.bscanner

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.*
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aminivan.bscanner.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "MainActivity"

private const val RATIO_4_3_VALUE = 4.0 / 3.0
private const val RATIO_16_9_VALUE = 16.0 / 9.0
const val BARCODE_QR_CONTENT = "bar-code-qr-content"

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraSelector: CameraSelector
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var previewUseCase: Preview
    private lateinit var analysisUseCase: ImageAnalysis
    private var flashEnabled = false
    var isAlertDialogVisible = false // Initialize the flag


    private val screenAspectRatio: Int by lazy {
        val metrics = DisplayMetrics().also { binding.previewView.display?.getRealMetrics(it) }
        aspectRatio(metrics.widthPixels, metrics.heightPixels)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCamera()


    }
    private fun setupCamera() {
        cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindCameraUseCases() {
        bindPreviewUseCase()
        bindAnalyseUseCase()
    }

    private fun bindPreviewUseCase() {
        if (::previewUseCase.isInitialized)
            cameraProvider.unbind(previewUseCase)

        previewUseCase = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            // Fixed: java.lang.NullPointerException: Attempt to invoke virtual method 'int android.view.Display.getRotation()' on a null object reference
            // After the code was successfully scanned, I attempted to scan it again and caught this exception.
            // So I fixed with Rotation value '0' - this works in all cases.
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        previewUseCase.setSurfaceProvider(binding.previewView.surfaceProvider)

        try {
            val camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                previewUseCase
            )
            if (camera.cameraInfo.hasFlashUnit()) {
                binding.ivFlashControl.visibility = View.VISIBLE

                binding.ivFlashControl.setOnClickListener {
                    camera.cameraControl.enableTorch(!flashEnabled)
                }

                camera.cameraInfo.torchState.observe(this) {
                    it?.let { torchState ->
                        if (torchState == TorchState.ON) {
                            flashEnabled = true
                            binding.ivFlashControl.setImageResource(R.drawable.ic_round_flash_on)
                        } else {
                            flashEnabled = false
                            binding.ivFlashControl.setImageResource(R.drawable.ic_round_flash_off)
                        }
                    }
                }
            }

        } catch (illegalStateException: IllegalStateException) {
            illegalStateException.printStackTrace()
        } catch (illegalArgumentException: IllegalArgumentException) {
            illegalArgumentException.printStackTrace()
        }
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun bindAnalyseUseCase() {
        val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient()

        if (::analysisUseCase.isInitialized)
            cameraProvider.unbind(analysisUseCase)

        analysisUseCase = ImageAnalysis.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(Surface.ROTATION_0) // Fixed: java.lang.NullPointerException: Attempt to invoke virtual method 'int android.view.Display.getRotation()' on a null object reference
            .build()

        val cameraExecutor = Executors.newSingleThreadExecutor()

        analysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(barcodeScanner, imageProxy)
        }
        try {
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                analysisUseCase
            )
        } catch (illegalStateException: IllegalStateException) {
            illegalStateException.printStackTrace()
        } catch (illegalArgumentException: IllegalArgumentException) {
            illegalArgumentException.printStackTrace()
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(
        barcodeScanner: BarcodeScanner,
        imageProxy: ImageProxy
    ) {
        imageProxy.image?.let { image ->
            val inputImage =
                InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)

            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    barcodes.forEach { barcode ->
                        val rawValue = barcode.rawValue

                            // Check if the Alert dialog is already visible
                            if (!isAlertDialogVisible) {
                                val resultIntent = Intent().apply {
                                    this.putExtra(BARCODE_QR_CONTENT, rawValue)
                                }
                                setResult(Activity.RESULT_OK, resultIntent)

                                Log.d(TAG, "processImageProxy: result $rawValue")

                                // Show an Alert dialog instead of a Toast message
                                val alertDialogBuilder = AlertDialog.Builder(this)
                                alertDialogBuilder.setTitle("Barcode Detected")
                                alertDialogBuilder.setMessage("Result: $rawValue")
                                alertDialogBuilder.setPositiveButton("OK") { dialog, _ ->
                                    dialog.dismiss()
                                    isAlertDialogVisible = false // Set the flag to false when dismissed
                                }

                                val alertDialog = alertDialogBuilder.create()
                                alertDialog.show()

                                isAlertDialogVisible = true // Set the flag to true when dialog is displayed
                            }
                        }

                }
                .addOnFailureListener { error ->
                    error.printStackTrace()
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }


}
