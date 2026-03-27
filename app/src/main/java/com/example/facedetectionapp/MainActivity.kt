package com.example.facedetectionapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult // ★ 추가됨
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts // ★ 추가됨
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigator()
                }
            }
        }
    }
}

// 화면 상태를 정의합니다
enum class ScreenState {
    HOME, PHONE_CAMERA, GLASSES
}

@Composable
fun AppNavigator() {
    // 처음 앱을 켜면 'HOME(메뉴)' 화면을 보여줌
    var currentScreen by remember { mutableStateOf(ScreenState.HOME) }

    when (currentScreen) {
        ScreenState.HOME -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "인맥 관리 비서 🤖",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 40.dp)
                )

                // 1. 스마트폰 카메라 모드 버튼
                Button(
                    onClick = { currentScreen = ScreenState.PHONE_CAMERA },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(60.dp)
                ) {
                    Text("📱 스마트폰 카메라로 인식", fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 2. 메타 안경 모드 버튼
                Button(
                    onClick = { currentScreen = ScreenState.GLASSES },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)), // 눈에 띄는 보라색
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(60.dp)
                ) {
                    Text("🕶️ 메타 안경 연동 모드", fontSize = 18.sp)
                }
            }
        }

        ScreenState.PHONE_CAMERA -> {
            // 뒤로 가기 누르면 다시 홈으로
            BackHandler { currentScreen = ScreenState.HOME }
            PhoneCameraWrapper()
        }

        ScreenState.GLASSES -> {
            // 뒤로 가기 누르면 다시 홈으로
            BackHandler { currentScreen = ScreenState.HOME }
            GlassesAssistantScreen()
        }
    }
}

// 스마트폰 카메라 권한을 이 안에서만 처리하도록 격리
@Composable
fun PhoneCameraWrapper() {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // ★ it 대신 isGranted로 명확하게 수정!
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasPermission) {
        // 기존에 만들어두신 카메라 화면 실행
        CameraScreen()
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            Text(
                text = "스마트폰 카메라 모드를 사용하려면\n권한이 필요합니다.",
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text("권한 허용하기")
            }
        }
    }
}