package com.example.plantdiseaseclassifier

import android.annotation.SuppressLint
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
import android.app.Activity
import android.Manifest

data class ClassEntry(val col1: String, val col2: String)

class MainActivity : ComponentActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraController: LifecycleCameraController
    private val REQUEST_ENABLE_BT = 1

//    private val bluetoothEnableLauncher = registerForActivityResult(
//        ActivityResultContracts.StartActivityForResult()
//    ) { result ->
//        if (result.resultCode == Activity.RESULT_OK) {
//            // Bluetooth was enabled
//        } else {
//            Toast.makeText(this, "Please enable Bluetooth to proceed", Toast.LENGTH_SHORT).show()
//        }
//    }




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
        val bluetoothAdapter : BluetoothAdapter? = bluetoothManager.getAdapter()
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
        } else {
            // Request BLUETOOTH_CONNECT permission first
            activityResultLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
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

    @SuppressLint("SetTextI18n")
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

            val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: -1
            val confidence = if (maxIndex >= 0) probabilities[maxIndex] else 0f
            if (confidence > 0.65) {
                viewBinding.classified.text = "Plant Species: " + classData[maxIndex].col1
                viewBinding.disease.text = "Disease: " + classData[maxIndex].col2
                viewBinding.confidence.text = "Confidence: $confidence"
            } else {
                Toast.makeText(this, "Prediction confidence below threshold", Toast.LENGTH_LONG).show()
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
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                android.Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()

        fun hasPermissions(context: Context) = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
