package com.example.facedetectionapp

import android.Manifest
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.facedetectionapp.data.PersonViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ★ Meta SDK 임포트
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

@Composable
fun GlassesAssistantScreen(
    viewModel: PersonViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val application = context.applicationContext as Application
    val registeredPeople by viewModel.allPeople.collectAsState()
    val scope = rememberCoroutineScope()

    // --- 디버깅 로그 시스템 ---
    var debugLog by remember { mutableStateOf("=== 원점: 물리버튼 트리거 모드 ===\n") }
    val scrollState = rememberScrollState()

    fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        debugLog += "[$time] $msg\n"
        Log.d("GLASSES_DEBUG", msg)
    }

    var isGlassesStreaming by remember { mutableStateOf(false) }
    var isDatCameraGranted by remember { mutableStateOf(false) }
    var permissionsGranted by remember { mutableStateOf(false) }

    var streamSession by remember { mutableStateOf<StreamSession?>(null) }
    var latestFrame by remember { mutableStateOf<Bitmap?>(null) }

    // ★ 프레임 들어오는지 확인용 계기판
    var frameCount by remember { mutableIntStateOf(0) }

    var lastRecognizedName by remember { mutableStateOf("대기 중...") }
    var lastRecognizedTime by remember { mutableStateOf(0L) }

    val classifier = remember { FaceClassifier(context) }
    val detector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        )
    }

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) { tts?.language = Locale.KOREAN }
        }
        tts = textToSpeech
        onDispose { textToSpeech.shutdown() }
    }

    fun speakToGlasses(name: String, info: String) {
        val currentTime = System.currentTimeMillis()
        if (name == lastRecognizedName && (currentTime - lastRecognizedTime) < 5000) return
        lastRecognizedName = name
        lastRecognizedTime = currentTime
        appendLog("🗣️ 브리핑: $name")
        tts?.speak("$name 님입니다. $info", TextToSpeech.QUEUE_FLUSH, null, "glasses_briefing")
    }

    fun processImageFromGlasses(glassesBitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(glassesBitmap, 0)
        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    // 로그 폭탄 방지를 위해 30프레임마다 한 번씩만 출력
                    if (frameCount % 30 == 0) appendLog("👀 영상은 오는데 얼굴이 안 보임...")
                    return@addOnSuccessListener
                }

                appendLog("😎 얼굴 감지 완료! DB 대조 중...")
                val face = faces[0]
                val rect = face.boundingBox
                val safeX = rect.left.coerceAtLeast(0)
                val safeY = rect.top.coerceAtLeast(0)
                val safeWidth = rect.width().coerceAtMost(glassesBitmap.width - safeX)
                val safeHeight = rect.height().coerceAtMost(glassesBitmap.height - safeY)

                if (safeWidth > 0 && safeHeight > 0) {
                    val croppedFace = Bitmap.createBitmap(glassesBitmap, safeX, safeY, safeWidth, safeHeight)
                    val currentEmbedding = classifier.getFaceEmbedding(croppedFace)

                    var bestMatchName = "Unknown"
                    var bestMatchInfo = ""
                    var globalMinDistance = Float.MAX_VALUE

                    for (person in registeredPeople) {
                        var localMinDistance = Float.MAX_VALUE
                        for (vector in person.embeddings) {
                            val distance = classifier.calculateDistance(currentEmbedding, vector)
                            if (distance < localMinDistance) localMinDistance = distance
                        }
                        if (localMinDistance < globalMinDistance) {
                            globalMinDistance = localMinDistance
                            bestMatchName = person.name
                            bestMatchInfo = person.info
                        }
                    }

                    if (globalMinDistance < 0.85f && bestMatchName != "Unknown") {
                        appendLog("🎉 매칭 성공: $bestMatchName")
                        speakToGlasses(bestMatchName, bestMatchInfo)
                    }
                }
            }
    }

    val datPermissionLauncher = rememberLauncherForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        appendLog("메타 권한 팝업 결과: $status")
        if (status == PermissionStatus.Granted) isDatCameraGranted = true
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        permissionsGranted = perms.values.all { it }
        appendLog("안드로이드 권한 획득: $permissionsGranted")
    }

    LaunchedEffect(Unit) {
        val req = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            req.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        }
        permissionLauncher.launch(req.toTypedArray())
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && activity != null) {
            try {
                Wearables.initialize(context)
                Wearables.startRegistration(activity)
                appendLog("✅ SDK 초기화 및 등록 완료")

                val checkResult = Wearables.checkPermissionStatus(Permission.CAMERA)
                if (checkResult.getOrNull() == PermissionStatus.Granted) {
                    isDatCameraGranted = true
                    appendLog("✅ 메타 카메라 권한 이미 있음")
                } else {
                    datPermissionLauncher.launch(Permission.CAMERA)
                }
            } catch (e: Exception) {
                appendLog("초기화 에러: ${e.message}")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            streamSession?.close()
            if (activity != null) { try { Wearables.startUnregistration(activity) } catch (e: Exception) {} }
        }
    }

    // ======================================================
    // UI Layout
    // ======================================================
    Scaffold(containerColor = Color(0xFF121212)) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isGlassesStreaming) Icons.Filled.BluetoothConnected else Icons.Filled.BluetoothDisabled,
                    contentDescription = null,
                    tint = if (isGlassesStreaming) Color(0xFF00E676) else Color.Gray,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isGlassesStreaming) "스트리밍 파이프 열림 🕶️" else "대기 중...",
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (!isDatCameraGranted) {
                        appendLog("❌ 메타 카메라 권한이 없습니다.")
                        return@Button
                    }

                    scope.launch {
                        try {
                            appendLog("🚀 안경에 스트리밍 연결 요청 중...")
                            val session = Wearables.startStreamSession(
                                application, AutoDeviceSelector(),
                                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24)
                            )
                            streamSession = session
                            isGlassesStreaming = true
                            appendLog("✅ 파이프 연결됨! (수신 대기)")

                            session.videoStream.collect { videoFrame ->
                                frameCount++
                                if (frameCount % 30 == 1) {
                                    appendLog("🎥 영상 수신 중! (현재 ${frameCount}프레임)")
                                }
                                val bitmap = YuvToBitmapConverter.convert(videoFrame.buffer, videoFrame.width, videoFrame.height)
                                if (bitmap != null) {
                                    latestFrame = bitmap
                                    processImageFromGlasses(bitmap)
                                }
                            }
                        } catch (e: Exception) {
                            appendLog("❌ 연결 실패: ${e.message}")
                            isGlassesStreaming = false
                        }
                    }
                },
                enabled = !isGlassesStreaming,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4081)),
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isGlassesStreaming) "프레임 수신 중 ($frameCount)" else "🚀 1단계: 연결 파이프 열기",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Text(
                    text = debugLog,
                    color = Color(0xFF00FF00),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp).verticalScroll(scrollState)
                )
            }
        }
    }
}