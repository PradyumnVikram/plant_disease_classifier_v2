package com.example.plantdiseaseclassifier

import android.annotation.SuppressLint
import android.graphics.Color
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.net.Uri
import android.util.Log
import android.widget.Toast
import java.util.Calendar
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.plantdiseaseclassifier.databinding.ActivityMainBinding
import com.example.plantdiseaseclassifier.ml.ModelPdcs
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import androidx.core.graphics.scale
import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.Manifest
import android.bluetooth.BluetoothDevice
import java.util.UUID
import android.bluetooth.BluetoothSocket
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import java.io.IOException
import android.os.Handler
import android.os.Looper
import android.view.View
import java.io.InputStream


data class ClassEntry(val col1: String, val col2: String)

class MainActivity : ComponentActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraController: LifecycleCameraController
    private val REQUEST_ENABLE_BT = 1

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val handler = Handler(Looper.getMainLooper())

//    private val bluetoothEnableLauncher = registerForActivityResult(
//        ActivityResultContracts.StartActivityForResult()
//    ) { result ->
//        if (result.resultCode == Activity.RESULT_OK) {
//            // Bluetooth was enabled
//        } else {
//            Toast.makeText(this, "Please enable Bluetooth to proceed", Toast.LENGTH_SHORT).show()
//        }
//    }




    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        if (!hasPermissions(baseContext)) {
            activityResultLauncher.launch(REQUIRED_PERMISSIONS)
        } else {
            startCamera()
        }
        val classData = readCSVFile()
        viewBinding.pictureButton.setOnClickListener {
            takePhoto(classData)
        }

        // getSystemService is called a context object to provide access to the Bluetooth service
        val bluetoothManager : BluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null){
            Log.e("BluetoothTest", "Bluetooth connection failed")
            Toast.makeText(this, "Bluetooth connection failed", Toast.LENGTH_SHORT).show()
        }

        // Now have to check if bluetooth is enabled ON SYS

//        if (bluetoothAdapter?.isEnabled == false) {
//            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
//            bluetoothEnableLauncher.launch(enableBtIntent)
//        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            if (bluetoothAdapter?.isEnabled == false) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
            else{
                connectToHC05()
            }
        }
    }

    private fun getPairedHC05Device(): BluetoothDevice? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val pairedDevices = bluetoothAdapter?.bondedDevices
            val device = pairedDevices?.find { it.name.contains("PDC") }
            if (device != null) {
                Toast.makeText(this, "HC-05 device found in paired devices!", Toast.LENGTH_SHORT).show()
            }
            return device
        } else {
            return null
        }
    }

    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @RequiresApi(Build.VERSION_CODES.S)
    private fun connectToHC05() {
        Toast.makeText(this, "Connection Process Started !", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Connection Process Started !")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val hc05Device = getPairedHC05Device()
            if (hc05Device == null) {
                Toast.makeText(this, "HC-05 device not found in paired devices", Toast.LENGTH_SHORT).show()
                return
            }

            val connectThread = ConnectThread(hc05Device)
            connectThread.start()

        } else {
            // Request permission first before connecting
            activityResultLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
    }

    private inner class ConnectThread(device: BluetoothDevice) : Thread() {

        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(MY_UUID)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
        public override fun run() {
            // Cancel discovery because it otherwise slows down the connection.
            bluetoothAdapter?.cancelDiscovery()

            mmSocket?.let { socket ->
                socket.connect()
                handler.post {
                    Toast.makeText(this@MainActivity, "Connected to PDC!", Toast.LENGTH_SHORT).show()
                }
                // Background thread cannot talk to UI, only main thread can, this is why it was crashing
                // Toast.makeText(this@MainActivity, "connected", Toast.LENGTH_SHORT).show()
                manageMyConnectedSocket(socket)
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket", e)
            }
        }
    }


    private fun manageMyConnectedSocket(socket: BluetoothSocket){
        Log.d(TAG, "Ready to hear from Arduino")
        val inputStream = socket.inputStream
        val buffer = ByteArray(1024)
        while (true) {
            val numBytes = try {
                // As soon as data arrives in the buffer it will be read, buffer does not need to be filled
                inputStream.read(buffer)
            } catch (e: IOException) {
                break
            }

            val receivedData = String(buffer, 0, numBytes).trim()
            //Toast.makeText(this@MainActivity, receivedData, Toast.LENGTH_SHORT).show()
            if (receivedData == "1;") {
                handler.post {
                    Toast.makeText(this@MainActivity, "Taking Photo!", Toast.LENGTH_SHORT).show()
                    // I've kept this the way it was in on-create
                    val classData = readCSVFile()
                    takePhoto(classData)
                }
            }
        }
    }




    private fun readCSVFile(): List<ClassEntry> {
        val inputStream = assets.open("classes.csv")
        val reader = csvReader()
        val classList = mutableListOf<ClassEntry>()

        reader.open(inputStream) {
            readAllAsSequence().forEach { row ->
                if (row.size >= 2) {
                    classList.add(ClassEntry(row[0], row[1]))
                }
            }
        }
        return classList
    }




    @SuppressLint("SetTextI18n", "DefaultLocale")
    private fun makePrediction(imageUri: Uri, classData: List<ClassEntry>) {
        try {
            val bitmap = loadScaledBitmapFromUri(imageUri, 224, 224)
            if (bitmap == null) {
                Toast.makeText(this, "Failed to load image for prediction", Toast.LENGTH_SHORT).show()
                return
            }

            val scaledBitmap = bitmap.scale(224, 224)

            val pixels = IntArray(224 * 224)
            scaledBitmap.getPixels(pixels, 0, 224, 0, 0, 224, 224)

            val floatValues = FloatArray(224 * 224 * 3)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                floatValues[i * 3] = (pixel shr 16 and 0xFF).toFloat()
                floatValues[i * 3 + 1] = (pixel shr 8 and 0xFF).toFloat()
                floatValues[i * 3 + 2] = (pixel and 0xFF).toFloat()
            }

            val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, 224, 224, 3), DataType.FLOAT32)
            inputFeature0.loadArray(floatValues)

            val model = ModelPdcs.newInstance(this)
            val outputs = model.process(inputFeature0)
            val outputFeature0 = outputs.outputFeature0AsTensorBuffer
            val probabilities = outputFeature0.floatArray
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            val second = calendar.get(Calendar.SECOND)
            val currentTime = String.format("%02d:%02d:%02d", hour, minute, second)
            var entry = ""
            val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: -1
            val confidence = if (maxIndex >= 0) probabilities[maxIndex] else 0f
            if (confidence > 0.0) {


                viewBinding.confidence.text = "Confidence: $confidence"
                if(classData[maxIndex].col2 == "Healthy") {
                    entry = "Health Status: Healthy"
                    viewBinding.classified.text = entry
                    viewBinding.classified.setBackgroundColor(Color.GREEN)
                    viewBinding.scrollableText?.append("[$currentTime] $entry\n")
                    viewBinding.logger?.post {
                        viewBinding.logger?.fullScroll(View.FOCUS_DOWN)
                    }
                }
                else{
                    entry = "Health Status: Diseased"
                    viewBinding.classified.text = entry
                    viewBinding.classified.setBackgroundColor(Color.RED)
                    viewBinding.scrollableText?.append("[$currentTime] $entry\n")
                    viewBinding.logger?.post {
                        viewBinding.logger?.fullScroll(View.FOCUS_DOWN)
                    }

                }

            } else {
                entry = "Prediction confidence below threshold"
                Toast.makeText(this, entry, Toast.LENGTH_LONG).show()
                viewBinding.classified.setBackgroundColor(Color.WHITE)
                viewBinding.scrollableText?.append("[$currentTime] $entry\n")
                viewBinding.logger?.post {
                    viewBinding.logger?.fullScroll(View.FOCUS_DOWN)
                }
            }
            model.close()
        } catch (e: Exception) {
            Log.e(TAG, "Model prediction failed", e)
            Toast.makeText(this, "Prediction failed: ${e.message}", Toast.LENGTH_LONG).show()

        }
    }

    private fun loadScaledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)

            options.inJustDecodeBounds = false
            contentResolver.openInputStream(uri).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun takePhoto(classData: List<ClassEntry>){
        val name = "PlantDisease" + System.currentTimeMillis()
        val contentValues = ContentValues().apply{
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P){
                put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/PDC-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        cameraController.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object: ImageCapture.OnImageSavedCallback {
                override fun onError(error: ImageCaptureException) {
                    Toast.makeText(baseContext,"Error",Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri
                    if (savedUri != null) {
                        makePrediction(savedUri, classData)
                    }
                }
            }
        )

    }

    private fun startCamera() {
        val previewView: PreviewView = viewBinding.previewView
        cameraController = LifecycleCameraController(baseContext)
        cameraController.bindToLifecycle(this)
        previewView.controller = cameraController
    }
    @RequiresApi(Build.VERSION_CODES.S)
    private val activityResultLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
            permissions ->
        var permissionGranted = true
        permissions.entries.forEach{
            if(it.key in REQUIRED_PERMISSIONS && !it.value)
                permissionGranted = false
        }
        if(!permissionGranted){
            Toast.makeText(this, "Permission request denied", Toast.LENGTH_SHORT).show()
        } else{
            startCamera()
        }
    }
    companion object {
        private const val TAG = "PDC"
        @RequiresApi(Build.VERSION_CODES.S)
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()

        @RequiresApi(Build.VERSION_CODES.S)
        fun hasPermissions(context: Context) = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
