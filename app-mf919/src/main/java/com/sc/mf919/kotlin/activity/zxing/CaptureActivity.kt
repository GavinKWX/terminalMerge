package com.sc.mf919.kotlin.activity.zxing

import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.*
import com.sc.mf919.R
import java.util.*


class CaptureActivity: Activity() {
    lateinit var capture: CaptureManager
    lateinit var barcodeView: DecoratedBarcodeView
    lateinit var viewFinderView: ViewfinderView

    //Button
    var isFlashOn = false
    lateinit var flashButton: Button
    var isBackCamera = true
    lateinit var cameraFlip: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_zxing)

        val formats: Collection<BarcodeFormat> = Arrays.asList(BarcodeFormat.QR_CODE, BarcodeFormat.PDF_417, BarcodeFormat.CODE_39, BarcodeFormat.CODE_128)
        barcodeView = findViewById<DecoratedBarcodeView>(R.id.dbv_custom)
        barcodeView.barcodeView.decoderFactory = DefaultDecoderFactory(formats)
        viewFinderView = findViewById(R.id.zxing_viewfinder_view)

        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()

        if (hasFlash()) {
            flashButton = findViewById<Button>(R.id.toggle_flash)
            flashButton.visibility = View.VISIBLE
            flashButton.setOnClickListener {
                if (!isFlashOn) {
                    isFlashOn = true
                    barcodeView.setTorchOn()
                    it.setBackgroundResource(R.drawable.flash_off)
                } else {
                    isFlashOn = false
                    barcodeView.setTorchOff()
                    it.setBackgroundResource(R.drawable.flash_on)
                }
            }
        }

        isBackCamera = barcodeView.cameraSettings.requestedCameraId == Camera.CameraInfo.CAMERA_FACING_BACK
        cameraFlip = findViewById<Button>(R.id.toggle_camera)
        cameraFlip.setOnClickListener {
            barcodeView.pauseAndWait()
            if(isBackCamera){
                isBackCamera = false
                val sets = barcodeView.barcodeView.cameraSettings
                sets.requestedCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT
                barcodeView.barcodeView.cameraSettings = sets
            } else {
                isBackCamera = false
                val sets = barcodeView.barcodeView.cameraSettings
                sets.requestedCameraId = Camera.CameraInfo.CAMERA_FACING_BACK
                barcodeView.barcodeView.cameraSettings = sets
            }
            barcodeView.resume()
        }

        changeLaserVisibility(false)
        findViewById<LinearLayout>(R.id.lly_back).setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return barcodeView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    fun changeLaserVisibility(visible: Boolean) {
        viewFinderView.setLaserVisibility(visible)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun hasFlash(): Boolean {
        return applicationContext.packageManager
            .hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }
}