package com.example.camerastream

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Base64
import android.widget.TextView
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var server: ServerSocket
    private var latestFrame: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this)
        text.text = "Streaming on port 8080"
        setContentView(text)

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)

        startCamera()
        startServer()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build()

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analyzer.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                val buffer = image.planes[0].buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)

                val yuv = YuvToRgbConverter(this)
                val bmp = Bitmap.createBitmap(
                    image.width, image.height, Bitmap.Config.ARGB_8888
                )
                yuv.yuvToRgb(image, bmp)

                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                latestFrame = out.toByteArray()

                image.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            provider.bindToLifecycle(this, cameraSelector, preview, analyzer)

        }, ContextCompat.getMainExecutor(this))
    }

    private fun startServer() {
        thread {
            server = ServerSocket(8080)
            while (true) {
                val client = server.accept()
                thread {
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\n".toByteArray())
                        write("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n".toByteArray())

                        while (true) {
                            val frame = latestFrame ?: continue
                            write("--frame\r\n".toByteArray())
                            write("Content-Type: image/jpeg\r\n".toByteArray())
                            write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                            write(frame)
                            write("\r\n\r\n".toByteArray())
                            flush()
                            Thread.sleep(33) // ~30 FPS
                        }
                    }
                    client.close()
                }
            }
        }
    }
}
