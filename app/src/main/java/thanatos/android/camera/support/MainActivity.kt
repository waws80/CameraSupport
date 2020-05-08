package thanatos.android.camera.support

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.support.annotation.RequiresPermission
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.Toast
import thanatos.android.camera.camera2.impl.Camera2Initializer
import thanatos.android.camera.core.*
import thanatos.android.camera.view.CameraView

class MainActivity : AppCompatActivity() {

    val cameraView by lazy { findViewById<CameraView>(R.id.cameraView) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 23) {
            initView()
        }else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA),100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED){
            initView()
        }
    }

    /**
     * 初始化view
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun initView(){
        cameraView.bindToLifecycle(this)
        cameraView.cameraLensFacing = CameraX.LensFacing.FRONT
        cameraView.captureMode = CameraView.CaptureMode.IMAGE
        cameraView.scaleType = CameraView.ScaleType.CENTER_CROP
        cameraView.isPinchToZoomEnabled = true
    }


    fun click(view: View){
        cameraView.takePicture(object : ImageCapture.OnImageCapturedListener(){
            override fun onCaptureSuccess(image: ImageProxy?, rotationDegrees: Int) {
                super.onCaptureSuccess(image, rotationDegrees)
                Toast.makeText(this@MainActivity, "success", Toast.LENGTH_SHORT).show()
            }

            override fun onError(
                imageCaptureError: ImageCapture.ImageCaptureError,
                message: String,
                cause: Throwable?
            ) {
                super.onError(imageCaptureError, message, cause)
                Toast.makeText(this@MainActivity, "error", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
