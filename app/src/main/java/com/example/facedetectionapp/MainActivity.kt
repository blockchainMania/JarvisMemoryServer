package com.example.facedetectionapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // 1. 권한 요청 결과 처리
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        shouldShowCamera.value = isGranted
    }

    // 2. 카메라 권한 상태 변수
    private val shouldShowCamera = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 앱 켜자마자 권한 있는지 확인
        checkPermissionStatus()

        setContent {
            // ★ [수정됨] FaceDetectionAppTheme 같은 복잡한 거 빼고
            // 안드로이드 기본 'MaterialTheme'을 사용합니다. 오류 해결!
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (shouldShowCamera.value) {
                        // 권한 있으면 -> 카메라 화면 (목록/카메라 기능 포함됨)
                        CameraScreen()
                    } else {
                        // 권한 없으면 -> 권한 요청 화면
                        PermissionRationaleUI()
                    }
                }
            }
        }
    }

    private fun checkPermissionStatus() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            shouldShowCamera.value = true
        } else {
            // 권한 없으면 앱 켜자마자 바로 물어보기
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 권한 없을 때 보여줄 화면
    @Composable
    fun PermissionRationaleUI() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            Text(
                text = "인맥 관리 비서를 사용하려면\n카메라 권한이 꼭 필요합니다.",
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Button(
                onClick = {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            ) {
                Text("권한 허용하기")
            }
        }
    }
}