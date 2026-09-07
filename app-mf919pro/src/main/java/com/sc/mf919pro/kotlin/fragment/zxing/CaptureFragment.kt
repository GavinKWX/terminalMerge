package com.sc.mf919pro.kotlin.fragment.zxing

import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.*
import com.sc.mf919pro.R
import com.sc.mf919pro.databinding.FragmentCaptureZxingBinding
import com.sc.mf919pro.kotlin.fragment.BaseFragment
import java.util.*


class CaptureFragment: BaseFragment() {
    lateinit var capture: CaptureManager
    lateinit var barcodeView: DecoratedBarcodeView
    lateinit var viewFinderView: ViewfinderView

    //Button
    var isFlashOn = false
    lateinit var flashButton: Button
    var isBackCamera = true
    lateinit var cameraFlip: Button

    private var _binding: FragmentCaptureZxingBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCaptureZxingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val formats: Collection<BarcodeFormat> = Arrays.asList(BarcodeFormat.QR_CODE, BarcodeFormat.PDF_417, BarcodeFormat.CODE_39, BarcodeFormat.CODE_128)
        barcodeView = view.findViewById(R.id.dbv_custom)
        barcodeView.barcodeView.decoderFactory = DefaultDecoderFactory(formats)
//        viewFinderView = view.findViewById(R.id.zxing_viewfinder_view)

        capture = CaptureManager(requireActivity(), barcodeView)
//        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()

        if (hasFlash()) {
            flashButton = view.findViewById<Button>(R.id.toggle_flash)
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

        isBackCamera = barcodeView.cameraSettings.requestedCameraId == 1
        cameraFlip = view.findViewById(R.id.toggle_camera)
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

//        changeLaserVisibility(false)
        view.findViewById<LinearLayout>(R.id.lly_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
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

//    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
//        return barcodeView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
//    }

//    fun changeLaserVisibility(visible: Boolean) {
//        viewFinderView.setLaserVisibility(visible)
//    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun hasFlash(): Boolean {
        return requireContext().packageManager
            .hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }
}