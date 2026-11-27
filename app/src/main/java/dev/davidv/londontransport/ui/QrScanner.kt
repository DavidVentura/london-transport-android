package dev.davidv.londontransport.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.util.Log
import dev.davidv.londontransport.santander.QrCode
import dev.davidv.londontransport.santander.bikeQrFromScan
import zxingcpp.BarcodeReader
import java.util.concurrent.Executors

@Composable
fun QrScannerDialog(onScanned: (QrCode) -> Unit, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    QrScanner(onScanned = onScanned, onCancel = onDismiss, modifier = Modifier.fillMaxSize())
  }
}

@Composable
private fun QrScanner(onScanned: (QrCode) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var permitted by rememberSaveable { mutableStateOf(hasCameraPermission(context)) }
  val request =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
  LaunchedEffect(permitted) { if (!permitted) request.launch(Manifest.permission.CAMERA) }

  Box(modifier.background(Color.Black)) {
    if (permitted) {
      CameraPreview(onScanned = onScanned, modifier = Modifier.fillMaxSize())
      Box(
        Modifier.align(Alignment.Center)
          .size(260.dp)
          .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(24.dp))
      )
      Text(
        "Point the camera at the QR code on the bike",
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 32.dp, end = 32.dp, bottom = 48.dp),
      )
    } else {
      Surface(Modifier.fillMaxSize()) {
        StatusMessage(
          icon = Icons.Outlined.QrCodeScanner,
          tone = Tone.Neutral,
          title = "Camera access needed",
          message = "The camera reads the QR code on the bike to unlock it.",
          primary = UiAction("Allow camera") { request.launch(Manifest.permission.CAMERA) },
          secondary = UiAction("Cancel", onClick = onCancel),
        )
      }
    }
    if (permitted) {
      IconButton(
        onClick = onCancel,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
        modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
      ) {
        Icon(Icons.Filled.Close, contentDescription = "Cancel")
      }
    }
  }
}

@Composable
private fun CameraPreview(onScanned: (QrCode) -> Unit, modifier: Modifier = Modifier) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val executor = remember { Executors.newSingleThreadExecutor() }
  var handled by remember { mutableStateOf(false) }

  DisposableEffect(Unit) { onDispose { executor.shutdown() } }

  AndroidView(
    modifier = modifier,
    factory = { ctx ->
      val previewView = PreviewView(ctx)
      val providerFuture = ProcessCameraProvider.getInstance(ctx)
      providerFuture.addListener({
        val provider = providerFuture.get()
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val analysis =
          ImageAnalysis.Builder()
            .setResolutionSelector(
              ResolutionSelector.Builder()
                .setResolutionStrategy(
                  ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor, QrAnalyzer { text ->
          val code = bikeQrFromScan(text) ?: return@QrAnalyzer
          previewView.post {
            if (!handled) {
              handled = true
              onScanned(code)
            }
          }
        })
        provider.unbindAll()
        val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        previewView.setOnTouchListener { view, event ->
          if (event.action == MotionEvent.ACTION_UP) {
            val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
            camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
            view.performClick()
          }
          true
        }
      }, ContextCompat.getMainExecutor(ctx))
      previewView
    },
  )
}

private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
  private val reader =
    BarcodeReader().apply {
      options = BarcodeReader.Options().apply {
        formats = setOf(BarcodeReader.Format.QR_CODE)
        tryHarder = true
        tryRotate = true
        tryInvert = true
      }
    }

  private var frames = 0

  override fun analyze(image: ImageProxy) {
    val w = image.width
    val h = image.height
    val fmt = image.format
    if (frames++ % 60 == 0) Log.d("QrScanner", "frame $frames fmt=$fmt ${w}x$h")
    try {
      image.use {
        val results = reader.read(it)
        results.firstOrNull()?.text?.let { text ->
          Log.d("QrScanner", "decoded: $text")
          onText(text)
        }
      }
    } catch (e: Throwable) {
      Log.w("QrScanner", "analyze failed fmt=$fmt ${w}x$h", e)
    }
  }
}

private fun hasCameraPermission(context: Context): Boolean =
  ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
