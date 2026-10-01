package com.example.transmitirvi

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.transmitirvi.ui.theme.TransmitirVITheme
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Arrays
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// --- MODELOS PROTOCOLO ROSBRIDGE (ROS 1) ---
data class RosStamp(val secs: Long, val nsecs: Long)
data class RosHeader(val seq: Int, val stamp: RosStamp, val frame_id: String? = null)
data class Vector3(val x: Double, val y: Double, val z: Double)
data class Quaternion(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0, val w: Double = 1.0)
data class RosImuMsg(
    val header: RosHeader,
    val angular_velocity: Vector3,
    val linear_acceleration: Vector3
)
data class RosRawImageMsg(
    val header: RosHeader,
    val height: Int,
    val width: Int,
    val encoding: String = "mono8",
    val is_bigendian: Int = 0,
    val step: Int,
    val data: String
)
data class RosGenericPublishMessage(val op: String = "publish", val topic: String, val msg: Any)
data class RosGenericAdvertiseMessage(val op: String = "advertise", val topic: String, val type: String)

enum class StreamStatus { Disconnected, Connecting, Connected, Streaming, Error, Retrying }

data class StreamUiState(
    val status: StreamStatus = StreamStatus.Disconnected,
    val serverIp: String = "192.168.20.40",
    val serverPort: String = "9090",
    val isStreaming: Boolean = false,
    val targetFps: Float = 15f,
    val targetImuHz: Float = 200f,
    val resolution: String = "640x480",
    val totalFramesSent: Int = 0,
    val totalImuSent: Int = 0,
    val torchEnabled: Boolean = false,
    val errorMessage: String? = null,
    val isRecording: Boolean = false,
    val totalFramesRecorded: Int = 0,
    val totalImuRecorded: Int = 0,
    val recordingPath: String? = null
)

// --- VIEWMODEL ---
class StreamViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {
    private val _uiState = MutableStateFlow(StreamUiState())
    val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var isStreamingActive = false
    private val cameraPublishingEnabled = AtomicBoolean(false)
    private val totalFramesCount = AtomicInteger(0)
    private var lastFrameTimeMs: Long = 0
    private val gson = Gson()

    private val camSeq = AtomicInteger(0)
    private val imuSeq = AtomicInteger(0)
    private val imuSentCount = AtomicInteger(0)
    private val senderExecutor = Executors.newSingleThreadExecutor()
    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Pipeline Optimizado
    private val pendingImage = AtomicReference<ImageProxy?>(null)
    private val isProcessing = AtomicBoolean(false)

    private var isAdvertised = false
    @Volatile private var lastAcc = Vector3(0.0, 0.0, 0.0)
    @Volatile private var lastGyro = Vector3(0.0, 0.0, 0.0)
    private var timeOffsetNs: Long = (System.currentTimeMillis() * 1_000_000L) - SystemClock.elapsedRealtimeNanos()
    private var lastImuTimeNs: Long = 0

    // Grabación
    private val recordingExecutor = Executors.newSingleThreadExecutor()
    private var camDataDir: File? = null
    private var imuCsvWriter: PrintWriter? = null
    private var camCsvWriter: PrintWriter? = null
    private val recordedFramesCount = AtomicInteger(0)
    private val recordedImuCount = AtomicInteger(0)

    fun setIp(ip: String) { if (!isStreamingActive) _uiState.update { it.copy(serverIp = ip) } }
    fun setPort(port: String) { if (!isStreamingActive) _uiState.update { it.copy(serverPort = port) } }
    fun setTargetFps(fps: Float) { _uiState.update { it.copy(targetFps = fps) } }
    fun setTargetImuHz(hz: Float) { _uiState.update { it.copy(targetImuHz = hz) } }
    fun setResolution(res: String) { if (!isStreamingActive) _uiState.update { it.copy(resolution = res) } }
    fun toggleTorch() { _uiState.update { it.copy(torchEnabled = !it.torchEnabled) } }

    private fun vibrate() {
        val context = getApplication<Application>()
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) it.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") it.vibrate(100)
            }
        } catch (e: Exception) { Log.e("Vibration", "Error", e) }
    }

    private fun startSensors() {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                accelerometer?.let { sensorManager.registerListener(this@StreamViewModel, it, SensorManager.SENSOR_DELAY_FASTEST) }
                gyroscope?.let { sensorManager.registerListener(this@StreamViewModel, it, SensorManager.SENSOR_DELAY_FASTEST) }
            } catch (e: Exception) {
                accelerometer?.let { sensorManager.registerListener(this@StreamViewModel, it, SensorManager.SENSOR_DELAY_GAME) }
                gyroscope?.let { sensorManager.registerListener(this@StreamViewModel, it, SensorManager.SENSOR_DELAY_GAME) }
            }
        }
    }

    private fun stopSensors() {
        if (!isStreamingActive && !uiState.value.isRecording) {
            viewModelScope.launch(Dispatchers.Main) { sensorManager.unregisterListener(this@StreamViewModel) }
        }
    }

    fun toggleStreaming() { if (isStreamingActive) stopStreaming() else startStreaming() }

    fun toggleRecording() {
        if (uiState.value.isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        // Carpeta Documentos/TransmitirVI_Datasets para visibilidad total en Samsung
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val baseDir = File(documentsDir, "TransmitirVI_Datasets")
        val datasetDir = File(baseDir, "dataset_${System.currentTimeMillis()}")
        camDataDir = File(datasetDir, "cam0/data").apply { mkdirs() }
        val imuDir = File(datasetDir, "imu0").apply { mkdirs() }
        
        try {
            camCsvWriter = PrintWriter(FileWriter(File(datasetDir, "cam0/data.csv")))
            camCsvWriter?.println("#timestamp [ns],filename")
            
            imuCsvWriter = PrintWriter(FileWriter(File(imuDir, "data.csv")))
            imuCsvWriter?.println("#timestamp,wx,wy,wz,ax,ay,az")
            
            recordedFramesCount.set(0)
            recordedImuCount.set(0)
            _uiState.update { it.copy(isRecording = true, totalFramesRecorded = 0, totalImuRecorded = 0, recordingPath = datasetDir.absolutePath) }
            startSensors()
            vibrate()
        } catch (e: Exception) {
            Log.e("Recording", "Error al crear archivos en Documentos", e)
            _uiState.update { it.copy(errorMessage = "Error de escritura: ¿Permisos?") }
        }
    }

    private fun stopRecording() {
        _uiState.update { it.copy(isRecording = false) }
        recordingExecutor.execute {
            try {
                camCsvWriter?.flush(); camCsvWriter?.close()
                imuCsvWriter?.flush(); imuCsvWriter?.close()
            } catch (e: Exception) { Log.e("Recording", "Error al cerrar", e) }
            camCsvWriter = null; imuCsvWriter = null
            stopSensors()
            vibrate()
        }
    }

    private fun startStreaming() {
        if (isStreamingActive) return
        isStreamingActive = true
        _uiState.update { it.copy(isStreaming = true, totalFramesSent = 0, totalImuSent = 0, status = StreamStatus.Connecting, errorMessage = null) }
        connect()
    }

    private fun connect() {
        if (!isStreamingActive) return
        webSocket?.close(1000, "Reset")
        val url = "ws://${uiState.value.serverIp.trim()}:${uiState.value.serverPort.trim()}"
        val request = try { Request.Builder().url(url).build() } catch (e: Exception) {
            _uiState.update { it.copy(status = StreamStatus.Error, errorMessage = "IP/Puerto inválidos") }
            isStreamingActive = false; return
        }
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(gson.toJson(RosGenericAdvertiseMessage(topic = "/cam0/image_raw/compressed", type = "sensor_msgs/Image")))
                webSocket.send(gson.toJson(RosGenericAdvertiseMessage(topic = "/imu0", type = "sensor_msgs/Imu")))
                isAdvertised = true
                cameraPublishingEnabled.set(false)
                
                viewModelScope.launch {
                    delay(2000)
                    if (isStreamingActive) cameraPublishingEnabled.set(true)
                }

                startSensors()
                _uiState.update { it.copy(status = StreamStatus.Connected, errorMessage = null) }
                vibrate()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isStreamingActive) {
                    _uiState.update { it.copy(status = StreamStatus.Error, errorMessage = t.localizedMessage) }
                    isAdvertised = false; cameraPublishingEnabled.set(false); stopSensors(); retry()
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isStreamingActive) {
                    _uiState.update { it.copy(status = StreamStatus.Disconnected) }
                    isAdvertised = false; cameraPublishingEnabled.set(false); stopSensors(); retry()
                }
            }
        })
    }

    private fun retry() { viewModelScope.launch { delay(3000); if (isStreamingActive) { _uiState.update { it.copy(status = StreamStatus.Retrying) }; connect() } } }

    private fun stopStreaming() {
        isStreamingActive = false; isAdvertised = false; cameraPublishingEnabled.set(false)
        webSocket?.close(1000, "Stop"); webSocket = null
        _uiState.update { it.copy(isStreaming = false, status = StreamStatus.Disconnected) }
        pendingImage.getAndSet(null)?.close()
        stopSensors()
    }

    fun processImage(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val minInterval = (1000f / uiState.value.targetFps).toLong()
        
        if ((!isStreamingActive || !cameraPublishingEnabled.get() || !isAdvertised) && !uiState.value.isRecording) {
            image.close(); return
        }
        
        if (now - lastFrameTimeMs < minInterval) {
            image.close(); return
        }

        if (isStreamingActive) {
            val qSize = webSocket?.queueSize() ?: 0
            if (qSize > 1024 * 1024) { image.close(); return }
        }

        val old = pendingImage.getAndSet(image)
        old?.close()

        if (isProcessing.compareAndSet(false, true)) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    while (isStreamingActive || uiState.value.isRecording) {
                        val img = pendingImage.getAndSet(null) ?: break
                        sendAndOrRecordImage(img)
                    }
                } finally { isProcessing.set(false) }
            }
        }
        lastFrameTimeMs = now
    }

    private fun sendAndOrRecordImage(image: ImageProxy) {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val ySize = width * height

        val nv21 = ByteArray(ySize + (ySize / 2))
        for (y in 0 until height) {
            yBuffer.position(y * rowStride)
            yBuffer.get(nv21, y * width, width)
        }

        val ts = image.imageInfo.timestamp + timeOffsetNs
        val seq = camSeq.incrementAndGet()
        image.close()
        
        Arrays.fill(nv21, ySize, nv21.size, 128.toByte())

        if (uiState.value.isRecording) {
            val nv21Copy = nv21.copyOf()
            recordingExecutor.execute { saveImageToFile(nv21Copy, width, height, ts) }
        }

        if (isStreamingActive && isAdvertised && cameraPublishingEnabled.get()) {
            try {
                val baos = ByteArrayOutputStream()
                val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                yuv.compressToJpeg(Rect(0, 0, width, height), 45, baos)
                val jpegBytes = baos.toByteArray()
                val base64Data = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                
                val rosMsg = RosGenericPublishMessage(
                    topic = "/cam0/image_raw/compressed",
                    msg = RosRawImageMsg(
                        header = RosHeader(seq, RosStamp(ts / 1_000_000_000, ts % 1_000_000_000), "camera"),
                        height = height, width = width, encoding = "jpeg", step = 0, data = base64Data
                    )
                )
                webSocket?.send(gson.toJson(rosMsg))
                _uiState.update { it.copy(status = StreamStatus.Streaming, totalFramesSent = totalFramesCount.incrementAndGet()) }
            } catch (e: Exception) { Log.e("WebSocket", "Fallo envío", e) }
        }
    }

    private fun saveImageToFile(nv21: ByteArray, width: Int, height: Int, ts: Long) {
        val ySize = width * height
        val pixels = IntArray(ySize)
        for (i in 0 until ySize) {
            val y = nv21[i].toInt() and 0xFF
            pixels[i] = 0xFF000000.toInt() or (y shl 16) or (y shl 8) or y
        }
        val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val file = File(camDataDir, "$ts.png")
        try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            camCsvWriter?.println("$ts,$ts.png")
            _uiState.update { it.copy(totalFramesRecorded = recordedFramesCount.incrementAndGet()) }
        } catch (e: Exception) { Log.e("Recording", "Fallo save", e) } finally { bmp.recycle() }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val type = event.sensor.type
        val values = event.values
        val hwTs = event.timestamp + timeOffsetNs

        // 1. Siempre actualizar el valor más reciente para evitar datos repetidos
        if (type == Sensor.TYPE_ACCELEROMETER) {
            lastAcc = Vector3(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            lastGyro = Vector3(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
        } else return

        val isStreaming = isStreamingActive && isAdvertised
        val isRecording = uiState.value.isRecording
        if (!isStreaming && !isRecording) return

        // 2. Controlar frecuencia de salida basada en el timestamp de hardware real
        val minIntervalNs = (1_000_000_000L / uiState.value.targetImuHz).toLong()
        if (hwTs - lastImuTimeNs < minIntervalNs) return 
        lastImuTimeNs = hwTs
        
        val currentAcc = lastAcc
        val currentGyro = lastGyro
        val seq = imuSeq.incrementAndGet()

        senderExecutor.execute {
            if (isStreaming) {
                // Mensaje IMU optimizado: sin orientación ni covarianzas
                val imuMsg = RosGenericPublishMessage(
                    topic = "/imu0", 
                    msg = RosImuMsg(
                        header = RosHeader(seq, RosStamp(hwTs / 1_000_000_000, hwTs % 1_000_000_000)), 
                        angular_velocity = currentGyro, 
                        linear_acceleration = currentAcc
                    )
                )
                if (webSocket?.send(gson.toJson(imuMsg)) == true) {
                    _uiState.update { it.copy(totalImuSent = imuSentCount.incrementAndGet()) }
                }
            }
            
            if (isRecording) {
                recordingExecutor.execute {
                    imuCsvWriter?.println("$hwTs,${currentGyro.x},${currentGyro.y},${currentGyro.z},${currentAcc.x},${currentAcc.y},${currentAcc.z}")
                    _uiState.update { it.copy(totalImuRecorded = recordedImuCount.incrementAndGet()) }
                }
            }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onCleared() { super.onCleared(); stopStreaming(); senderExecutor.shutdown(); recordingExecutor.shutdown() }
}

// --- ACTIVITY & UI ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mantiene la pantalla encendida mientras la app esté en primer plano
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            TransmitirVITheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionHandler { StreamScreen() }
                }
            }
        }
    }
}

@Composable
fun PermissionHandler(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        hasPermission = results.values.all { it }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        launcher.launch(permissions.toTypedArray())
        
        // Para Android 11+ y acceso a Documentos (Samsung), a veces es necesario solicitar MANAGE_EXTERNAL_STORAGE manualmente
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            }
        }
    }

    if (hasPermission || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())) {
        content()
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("Se requieren permisos de Cámara y Almacenamiento (Todos los archivos) para guardar en Documentos.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { 
                    launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                }) { Text("CONCEDER PERMISOS") }
            }
        }
    }
}

@Composable
fun StreamScreen(viewModel: StreamViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                Button(
                    onClick = { viewModel.toggleRecording() },
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (uiState.isRecording) Color(0xFFFF5722) else Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (uiState.isRecording) "DETENER GRABACIÓN" else "GRABAR DATOS (KALIBR)", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { viewModel.toggleStreaming() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (uiState.isStreaming) Color.Red else MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (uiState.isStreaming) "DETENER TRANSMISIÓN" else "CONECTAR / TRANSMITIR", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            val resSize = when(uiState.resolution) { "1280x720" -> Size(1280, 720); else -> Size(640, 480) }
            CameraPreviewBase(Modifier.fillMaxSize(), resSize, uiState.torchEnabled) { viewModel.processImage(it) }
            
            Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(16.dp)) {
                val (color, label) = when (uiState.status) { 
                    StreamStatus.Connected -> Color.Cyan to "CONECTADO"
                    StreamStatus.Streaming -> Color.Green to "TRANSMITIENDO"
                    StreamStatus.Connecting -> Color.Yellow to "CONECTANDO"
                    StreamStatus.Retrying -> Color.Magenta to "REINTENTANDO"
                    StreamStatus.Error -> Color.Red to "ERROR"
                    else -> Color.Gray to "DESCONECTADO" 
                }
                StatusTag(label, color)
                if (uiState.isRecording) {
                    Spacer(Modifier.height(4.dp))
                    StatusTag("GRABANDO", Color.Red)
                }
                Spacer(Modifier.height(8.dp))
                InfoTextItem("IP: ${uiState.serverIp}:${uiState.serverPort}")
                InfoTextItem("Frames: ${uiState.totalFramesSent}")
                InfoTextItem("IMU: ${uiState.totalImuSent}", Color.Yellow)
                
                if (uiState.isRecording || uiState.totalFramesRecorded > 0) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color.White.copy(alpha = 0.3f))
                    InfoTextItem("Rec Frames: ${uiState.totalFramesRecorded}")
                    InfoTextItem("Rec IMU: ${uiState.totalImuRecorded}", Color.Yellow)
                    uiState.recordingPath?.let { path ->
                        Text("Path: ...${path.takeLast(30)}", color = Color.LightGray, fontSize = 9.sp)
                    }
                }
                
                if (uiState.errorMessage != null) Text("Error: ${uiState.errorMessage}", color = Color.Red, fontSize = 10.sp, maxLines = 2)
            }

            IconButton(onClick = { viewModel.toggleTorch() }, Modifier.align(Alignment.TopEnd).padding(16.dp).background(if(uiState.torchEnabled) Color.Yellow else Color.Black.copy(alpha = 0.4f), CircleShape)) {
                Icon(Icons.Default.FlashOn, "Flash", tint = if(uiState.torchEnabled) Color.Black else Color.White) 
            }

            Card(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp, start = 16.dp, end = 16.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f)), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Transmitir Visual-Inercial (Mono8)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("FPS", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(40.dp))
                        Slider(value = uiState.targetFps, onValueChange = { viewModel.setTargetFps(it) }, valueRange = 1f..30f, steps = 29, modifier = Modifier.weight(1f))
                        Text("${uiState.targetFps.toInt()}", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(20.dp), textAlign = TextAlign.End)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("IMU Hz", color = Color.Yellow, fontSize = 10.sp, modifier = Modifier.width(40.dp))
                        Slider(value = uiState.targetImuHz, onValueChange = { viewModel.setTargetImuHz(it) }, valueRange = 50f..400f, steps = 350, modifier = Modifier.weight(1f))
                        Text("${uiState.targetImuHz.toInt()}", color = Color.Yellow, fontSize = 10.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                    }
                    Row(Modifier.padding(vertical = 4.dp)) {
                        TextField(value = uiState.serverIp, onValueChange = { viewModel.setIp(it) }, label = { Text("IP", fontSize = 10.sp) }, modifier = Modifier.weight(1f), enabled = !uiState.isStreaming, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp), singleLine = true)
                        Spacer(Modifier.width(8.dp))
                        TextField(value = uiState.serverPort, onValueChange = { viewModel.setPort(it) }, label = { Text("Port", fontSize = 10.sp) }, modifier = Modifier.width(80.dp), enabled = !uiState.isStreaming, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp), singleLine = true)
                    }
                    Row { listOf("640x480", "1280x720").forEach { res -> FilterChip(selected = uiState.resolution == res, onClick = { viewModel.setResolution(res) }, label = { Text(res, fontSize = 10.sp) }, modifier = Modifier.padding(end = 4.dp), enabled = !uiState.isStreaming) } }
                }
            }
        }
    }
}

@Composable
fun CameraPreviewBase(modifier: Modifier, targetRes: Size? = null, torchEnabled: Boolean = false, onFrame: (ImageProxy) -> Unit) {
    val context = LocalContext.current; val lifecycleOwner = LocalLifecycleOwner.current; val previewView = remember { PreviewView(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    LaunchedEffect(targetRes) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val strategy = targetRes?.let { ResolutionStrategy(it, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER) }
                val selector = strategy?.let { ResolutionSelector.Builder().setResolutionStrategy(it).build() }
                val preview = Preview.Builder().apply { selector?.let { setResolutionSelector(it) } }.build()
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).apply { selector?.let { setResolutionSelector(it) } }.build()
                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { onFrame(it) }
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                preview.setSurfaceProvider(previewView.surfaceProvider)
            } catch (e: Exception) { Log.e("Camera", "Fallo", e) }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }
    LaunchedEffect(torchEnabled, camera) { try { camera?.cameraControl?.enableTorch(torchEnabled) } catch (e: Exception) { } }
    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
fun StatusTag(label: String, color: Color) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).background(color, CircleShape)); Spacer(Modifier.width(8.dp)); Text(label, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) } }

@Composable
fun InfoTextItem(text: String, color: Color = Color.White) { Text(text, color = color, fontSize = 13.sp, fontWeight = if (color != Color.White) FontWeight.Bold else FontWeight.Normal) }
