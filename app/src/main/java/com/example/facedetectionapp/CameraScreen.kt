package com.example.facedetectionapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.facedetectionapp.data.Person
import com.example.facedetectionapp.data.PersonViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class FaceResult(val rect: Rect, val name: String, val info: String, val distance: Float)

@OptIn(ExperimentalGetImage::class, ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: PersonViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    // Room DB 명단
    val registeredPeople by viewModel.allPeople.collectAsState()

    // ★ [수정됨] true로 시작해야 앱 켜자마자 카메라가 뜹니다!
    var isCameraMode by remember { mutableStateOf(true) }

    // 팝업 상태
    var showInputDialog by remember { mutableStateOf(false) }
    var showDetailDialog by remember { mutableStateOf<Person?>(null) }

    // 카메라 변수
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }
    var faceResults by remember { mutableStateOf<List<FaceResult>>(emptyList()) }
    var currentFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedFaceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRegistrationMode by remember { mutableStateOf(false) }

    // 수정 모드
    var isEditingMode by remember { mutableStateOf(false) }
    var editingPersonId by remember { mutableIntStateOf(-1) }

    // 입력 필드
    var inputName by remember { mutableStateOf("") }
    var inputPhone by remember { mutableStateOf("") }
    var inputInfo by remember { mutableStateOf("") }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val classifier = remember { FaceClassifier(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // --- Helper Functions ---
    fun saveImageToStorage(bitmap: Bitmap, name: String) {
        try {
            val filename = "${name}.png"
            context.openFileOutput(filename, Context.MODE_PRIVATE).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun loadImageFromStorage(name: String): Bitmap? {
        return try {
            val filename = "${name}.png"
            val file = File(context.filesDir, filename)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        } catch (e: Exception) { null }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val (name, phone) = getContactInfo(context, uri)
                inputName = name
                inputPhone = phone
            }
        }
    }
    fun openContactPicker() {
        contactPickerLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
    }

    // ======================================================
    // UI 로직: isCameraMode 값에 따라 화면 전체를 스위칭
    // ======================================================

    if (isCameraMode) {
        // ###################################
        // [화면 A] 카메라 모드 (메인 홈)
        // ###################################

        // 카메라 초기화 로직
        LaunchedEffect(lensFacing, previewView, registeredPeople) {
            val pView = previewView ?: return@LaunchedEffect
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(pView.surfaceProvider)
                val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val fastOptions = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
                val detector = FaceDetection.getClient(fastOptions)

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        detector.process(inputImage).addOnSuccessListener { faces ->
                            val results = mutableListOf<FaceResult>()
                            val frameBitmap = imageProxy.toBitmap()
                            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
                            val rotatedFrame = if (rotation != 0f) rotateBitmap(frameBitmap, rotation) else frameBitmap
                            currentFrameBitmap = rotatedFrame

                            for (face in faces) {
                                val rect = face.boundingBox
                                val safeX = rect.left.coerceAtLeast(0)
                                val safeY = rect.top.coerceAtLeast(0)
                                val safeWidth = rect.width().coerceAtMost(rotatedFrame.width - safeX)
                                val safeHeight = rect.height().coerceAtMost(rotatedFrame.height - safeY)

                                if (safeWidth > 0 && safeHeight > 0) {
                                    val croppedFace = Bitmap.createBitmap(rotatedFrame, safeX, safeY, safeWidth, safeHeight)
                                    val currentEmbedding = classifier.getFaceEmbedding(croppedFace)
                                    var bestPerson: Person? = null
                                    var globalMinDistance = Float.MAX_VALUE
                                    for (person in registeredPeople) {
                                        var localMinDistance = Float.MAX_VALUE
                                        for (vector in person.embeddings) {
                                            val distance = classifier.calculateDistance(currentEmbedding, vector)
                                            if (distance < localMinDistance) localMinDistance = distance
                                        }
                                        if (localMinDistance < globalMinDistance) {
                                            globalMinDistance = localMinDistance
                                            bestPerson = person
                                        }
                                    }
                                    val isRecognized = (globalMinDistance < 0.85f && bestPerson != null)
                                    results.add(FaceResult(rect, if(isRecognized) bestPerson!!.name else "Unknown", if(isRecognized) bestPerson!!.info else "", globalMinDistance))
                                }
                            }
                            faceResults = results
                        }.addOnCompleteListener { imageProxy.close() }
                    } else imageProxy.close()
                }
                val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                try { cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis) } catch (e: Exception) { e.printStackTrace() }
            }, ContextCompat.getMainExecutor(context))
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val maxWidthPx = with(density) { maxWidth.toPx() }
            val maxHeightPx = with(density) { maxHeight.toPx() }

            // 터치 등록/수정
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        if (isRegistrationMode && currentFrameBitmap != null) {
                            val scaleX = maxWidthPx / 480f
                            val scaleY = maxHeightPx / 640f
                            val isFront = (lensFacing == CameraSelector.LENS_FACING_FRONT)
                            for (result in faceResults) {
                                if (result.name == "Unknown" && !isRegistrationMode) continue
                                val rect = result.rect
                                val left = if (isFront) maxWidthPx - (rect.right * scaleX) else rect.left * scaleX
                                val right = if (isFront) maxWidthPx - (rect.left * scaleX) else rect.right * scaleX
                                val top = rect.top * scaleY
                                val bottom = rect.bottom * scaleY

                                if (tapOffset.x in left..right && tapOffset.y in top..bottom) {
                                    try {
                                        val safeX = rect.left.coerceAtLeast(0)
                                        val safeY = rect.top.coerceAtLeast(0)
                                        val safeW = rect.width().coerceAtMost(currentFrameBitmap!!.width - safeX)
                                        val safeH = rect.height().coerceAtMost(currentFrameBitmap!!.height - safeY)
                                        if (safeW > 0 && safeH > 0) {
                                            selectedFaceBitmap = Bitmap.createBitmap(currentFrameBitmap!!, safeX, safeY, safeW, safeH)
                                            val existing = registeredPeople.find { it.name == result.name && result.name != "Unknown" }
                                            if (existing != null) {
                                                isEditingMode = true; editingPersonId = existing.id; inputName = existing.name; inputPhone = existing.phoneNumber ?: ""; inputInfo = existing.info
                                            } else {
                                                isEditingMode = false; inputName = ""; inputPhone = ""; inputInfo = ""
                                            }
                                            showInputDialog = true
                                        }
                                    } catch (e: Exception) { e.printStackTrace() }
                                    break
                                }
                            }
                        }
                    }
                }
            ) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }.also { previewView = it } })

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scaleX = size.width / 480f; val scaleY = size.height / 640f
                    val isFront = (lensFacing == CameraSelector.LENS_FACING_FRONT)
                    for (result in faceResults) {
                        if (!isRegistrationMode && result.name == "Unknown") continue
                        val rect = result.rect
                        val left = if (isFront) size.width - (rect.right * scaleX) else rect.left * scaleX
                        val right = if (isFront) size.width - (rect.left * scaleX) else rect.right * scaleX
                        val top = rect.top * scaleY
                        val bottom = rect.bottom * scaleY
                        drawRoundRect(
                            color = if (result.name == "Unknown") Color(0xFFFF5252) else Color(0xFF00E676),
                            topLeft = Offset(left, top), size = Size(right - left, bottom - top),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f, 20f), style = Stroke(width = 8f)
                        )
                    }
                }

                // AR 정보
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val scaleX = maxWidth.value / 480f; val scaleY = maxHeight.value / 640f
                    val isFront = (lensFacing == CameraSelector.LENS_FACING_FRONT)
                    for (result in faceResults) {
                        if (result.name != "Unknown") {
                            val rect = result.rect
                            val rightSideX = if (isFront) 480f - rect.left else rect.right.toFloat()
                            val topSideY = rect.top.toFloat()
                            val offsetX = (rightSideX * scaleX).dp; val offsetY = (topSideY * scaleY).dp
                            Column(modifier = Modifier.offset { IntOffset(offsetX.roundToPx() + 20, offsetY.roundToPx()) }.widthIn(max = 200.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                                Text(result.name, color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(result.info, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }

                // [카메라 화면 UI 버튼들]
                // 1. 카메라 전환
                IconButton(
                    onClick = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(50.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) { Icon(Icons.Filled.Refresh, "Switch", tint = Color.White) }

                // 2. 등록 모드
                FloatingActionButton(
                    onClick = { isRegistrationMode = !isRegistrationMode },
                    containerColor = if (isRegistrationMode) Color.Red else Color(0xFF2196F3),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(30.dp)
                ) { if (isRegistrationMode) Icon(Icons.Filled.Close, "닫기") else Icon(Icons.Filled.Add, "등록") }

                // 3. ★ [명단] 버튼 (누르면 목록 화면으로 전환)
                Button(
                    onClick = { isCameraMode = false }, // 화면 전환!
                    modifier = Modifier.align(Alignment.BottomStart).padding(30.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.9f), contentColor = Color.Black),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Filled.List, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("명단", fontWeight = FontWeight.Bold)
                }
            }
        }

    } else {
        // ###################################
        // [화면 B] 인맥 명단 리스트 모드
        // ###################################

        // 목록에서 뒤로가기 누르면 -> 카메라로 복귀
        BackHandler {
            isCameraMode = true
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("인맥관리비서", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                    navigationIcon = {
                        // 뒤로가기 버튼 (카메라로 복귀)
                        IconButton(onClick = { isCameraMode = true }) {
                            Icon(Icons.Filled.CameraAlt, "카메라로 이동")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.White,
                        titleContentColor = Color.Black
                    )
                )
            },
            floatingActionButton = {
                // 카메라(AR)로 돌아가는 버튼
                FloatingActionButton(
                    onClick = { isCameraMode = true },
                    containerColor = Color(0xFF2196F3),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "AR 카메라 실행")
                }
            }
        ) { padding ->
            if (registeredPeople.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)), contentAlignment = Alignment.Center) {
                    Text("등록된 인맥이 없습니다.\n카메라 화면에서 + 버튼을 눌러 추가하세요.", color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .background(Color(0xFFF5F5F5)),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(registeredPeople) { person ->
                        Card(
                            onClick = { showDetailDialog = person },
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(4.dp),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 프로필 이미지
                                val savedImage = loadImageFromStorage(person.name)
                                if (savedImage != null) {
                                    Image(
                                        bitmap = savedImage.asImageBitmap(), contentDescription = null,
                                        modifier = Modifier.size(64.dp).clip(CircleShape).border(1.dp, Color.LightGray, CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.size(64.dp).clip(CircleShape).background(Color(0xFFE0E0E0)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(20.dp))

                                // 텍스트 정보
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(person.name, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black)
                                    if (!person.phoneNumber.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Phone, null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(person.phoneNumber, color = Color.Gray, fontSize = 14.sp)
                                        }
                                    }
                                    if (person.info.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            person.info.replace("\n", " "),
                                            color = Color.DarkGray, fontSize = 14.sp, maxLines = 1,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                // 화살표 아이콘
                                Icon(Icons.Filled.ArrowForwardIos, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // ================= [팝업 (상세/입력)] =================

    // 1. 상세 정보 팝업
    if (showDetailDialog != null) {
        val person = showDetailDialog!!
        Dialog(onDismissRequest = { showDetailDialog = null }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    val savedImage = loadImageFromStorage(person.name)
                    if (savedImage != null) {
                        Image(bitmap = savedImage.asImageBitmap(), contentDescription = null, modifier = Modifier.size(150.dp).clip(RoundedCornerShape(16.dp)).border(2.dp, Color.Gray, RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                    } else {
                        Box(modifier = Modifier.size(150.dp).background(Color.LightGray, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(50.dp)) }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(person.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (!person.phoneNumber.isNullOrBlank()) Text(person.phoneNumber, fontSize = 16.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp)); Divider(); Spacer(modifier = Modifier.height(8.dp))
                    Text(person.info, fontSize = 16.sp, lineHeight = 24.sp)
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showDetailDialog = null }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray, contentColor = Color.Black)) { Text("닫기") }
                        Button(onClick = {
                            File(context.filesDir, "${person.name}.png").delete()
                            viewModel.deletePerson(person)
                            Toast.makeText(context, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                            showDetailDialog = null
                        }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252), contentColor = Color.White)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("삭제") } }
                    }
                }
            }
        }
    }

    // 2. 입력 팝업
    if (showInputDialog && selectedFaceBitmap != null) {
        Dialog(onDismissRequest = { showInputDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxWidth(0.9f).wrapContentHeight().clip(RoundedCornerShape(24.dp)), color = Color.White) {
                Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if(isEditingMode) "정보 수정" else "신규 인맥 등록", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Image(bitmap = selectedFaceBitmap!!.asImageBitmap(), contentDescription = "Face", modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, Color.Gray, RoundedCornerShape(12.dp)))
                    OutlinedButton(onClick = { openContactPicker() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.AccountCircle, null); Spacer(Modifier.width(8.dp)); Text("연락처 가져오기") }
                    OutlinedTextField(value = inputName, onValueChange = { inputName = it }, label = { Text("이름") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = inputPhone, onValueChange = { inputPhone = it }, label = { Text("전화번호") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = inputInfo, onValueChange = { inputInfo = it }, label = { Text("메모 / 직함") }, modifier = Modifier.fillMaxWidth().height(100.dp), singleLine = false)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(onClick = { showInputDialog = false }) { Text("취소") }
                        Button(onClick = {
                            if (inputName.isBlank()) { Toast.makeText(context, "이름을 입력하세요", Toast.LENGTH_SHORT).show(); return@Button }
                            val embedding = classifier.getFaceEmbedding(selectedFaceBitmap!!)
                            saveImageToStorage(selectedFaceBitmap!!, inputName)

                            if (isEditingMode) {
                                registeredPeople.find { it.id == editingPersonId }?.let { original ->
                                    val newEmbeddings = original.embeddings.toMutableList().apply { if (size < 5) add(embedding) }
                                    viewModel.updatePerson(
                                        original.copy(
                                            name = inputName,
                                            info = inputInfo,
                                            phoneNumber = inputPhone,
                                            embeddings = newEmbeddings
                                        )
                                    )
                                    Toast.makeText(context, "수정되었습니다.", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                viewModel.addPerson(
                                    name = inputName,
                                    info = inputInfo,
                                    phone = inputPhone,
                                    embeddings = listOf(embedding)
                                )
                                Toast.makeText(context, "등록되었습니다!", Toast.LENGTH_SHORT).show()
                            }
                            showInputDialog = false; isRegistrationMode = false
                        }) { Text("저장") }
                    }
                }
            }
        }
    }
}

// Helper Functions
@SuppressLint("Range")
fun getContactInfo(context: Context, contactUri: Uri): Pair<String, String> {
    var name = ""; var phone = ""
    try {
        val cursor = context.contentResolver.query(contactUri, null, null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            name = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: ""
            phone = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
            cursor.close()
        }
    } catch (e: Exception) { e.printStackTrace() }
    return Pair(name, phone)
}

fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix(); matrix.postRotate(degrees)
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}