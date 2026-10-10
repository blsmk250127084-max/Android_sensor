package com.example.razan

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MultiSensorAppScreen()
            }
        }
    }
}

@Composable
fun MultiSensorAppScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    // --- 1. SENSOR HARDWARE REFERENCES ---
    val accelSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val lightSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) }
    val proximitySensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) }

    val hasAccel = accelSensor != null
    val hasLight = lightSensor != null
    val hasProximity = proximitySensor != null

    // --- 2. SENSOR STATES ---
    var accelX by remember { mutableFloatStateOf(0f) }
    var accelY by remember { mutableFloatStateOf(0f) }

    var ambientLux by remember { mutableFloatStateOf(300f) } // Default 300 lux (normal indoor)
    var isProximityNear by remember { mutableStateOf(false) }
    var rawProximityDistance by remember { mutableFloatStateOf(5f) } // Raw distance value from hardware sensor

    // Smart Hybrid Proximity: Allows Light Sensor drop (< 15 Lux) to also trigger Proximity NEAR
    // (Helps modern smartphones with virtual/ultrasound proximity sensors that only trigger during phone calls)
    var enableLightAsProximityFallback by remember { mutableStateOf(true) }

    // Simulation Fallback States (For testing on emulators without specific sensors)
    var useSimulatedLight by remember { mutableStateOf(!hasLight) }
    var simulatedLux by remember { mutableFloatStateOf(80f) }

    var useSimulatedProximity by remember { mutableStateOf(!hasProximity) }
    var simulatedIsNear by remember { mutableStateOf(false) }

    // Sensor lifecycle registration state
    var isLifecycleResumed by remember { mutableStateOf(false) }

    // --- 3. LIFECYCLE MANAGEMENT (onResume / onPause) ---
    DisposableEffect(lifecycleOwner) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        accelX = event.values[0]
                        accelY = event.values[1]
                    }
                    Sensor.TYPE_LIGHT -> {
                        if (!useSimulatedLight) {
                            ambientLux = event.values[0]
                        }
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        if (!useSimulatedProximity) {
                            val distance = event.values[0]
                            val maxRange = event.sensor.maximumRange
                            rawProximityDistance = distance

                            // Robust proximity condition across various Android phone drivers
                            isProximityNear = (distance == 0f) || (maxRange > 0f && distance < maxRange) || (distance < 3f)
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isLifecycleResumed = true
                    // Register all 3 sensors onResume
                    accelSensor?.let {
                        sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
                    }
                    lightSensor?.let {
                        sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
                    }
                    proximitySensor?.let {
                        sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isLifecycleResumed = false
                    // Unregister all sensors onPause to save battery
                    sensorManager.unregisterListener(listener)
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(listener)
        }
    }

    // --- 4. COMPUTED EFFECTIVE SENSOR VALUES ---
    val effectiveLux = if (useSimulatedLight) simulatedLux else ambientLux

    // Hybrid Proximity: Physical Proximity OR Light Sensor covered by palm (< 15 Lux)
    val isLightCoveredNear = enableLightAsProximityFallback && (effectiveLux < 15f)
    val effectiveIsNear = if (useSimulatedProximity) simulatedIsNear else (isProximityNear || isLightCoveredNear)

    // Dynamic UI styling derived from Environment Sensor (Light / Lux)
    // Sesuai permintaan pengujian fisik:
    // Disenterin (Lux >= 100) -> UI jadi Gelap (Night Mode)
    // Kondisi Gelap (Lux < 100) -> UI jadi Terang (Day Mode)
    var invertLightLogic by remember { mutableStateOf(true) } // Default inverted as requested
    val isNightMode = if (invertLightLogic) effectiveLux >= 100f else effectiveLux < 100f

    val backgroundColor by animateColorAsState(
        targetValue = if (isNightMode) Color(0xFF121824) else Color(0xFFF4F6FB),
        animationSpec = tween(durationMillis = 600),
        label = "bgColor"
    )

    val cardBackgroundColor by animateColorAsState(
        targetValue = if (isNightMode) Color(0xFF1E2638) else Color.White,
        animationSpec = tween(durationMillis = 600),
        label = "cardBgColor"
    )

    val primaryTextColor by animateColorAsState(
        targetValue = if (isNightMode) Color(0xFFE2E8F0) else Color(0xFF1E293B),
        animationSpec = tween(durationMillis = 600),
        label = "textColor"
    )

    val subTextColor by animateColorAsState(
        targetValue = if (isNightMode) Color(0xFF94A3B8) else Color(0xFF64748B),
        animationSpec = tween(durationMillis = 600),
        label = "subTextColor"
    )

    val accentGlowColor by animateColorAsState(
        targetValue = if (effectiveIsNear) Color(0xFFEC4899) else if (isNightMode) Color(0xFF38BDF8) else Color(0xFF3B82F6),
        animationSpec = tween(durationMillis = 400),
        label = "accentColor"
    )

    // Ball movement calculation (Motion Sensor)
    val ballSize = 48.dp
    val ballRadiusPx = with(density) { (ballSize / 2).toPx() }

    var ballX by remember { mutableFloatStateOf(0f) }
    var ballY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Update ball position based on Accelerometer
    LaunchedEffect(accelX, accelY, effectiveIsNear, containerSize) {
        if (containerSize.width > 0 && containerSize.height > 0) {
            val maxX = containerSize.width.toFloat() - ballRadiusPx
            val maxY = containerSize.height.toFloat() - ballRadiusPx

            if (effectiveIsNear) {
                // Position Sensor Effect: Lock/Reset ball to center when Proximity is NEAR
                ballX = containerSize.width / 2f
                ballY = containerSize.height / 2f
            } else {
                val sensitivity = 2.5f
                val nextX = ballX - (accelX * sensitivity)
                val nextY = ballY + (accelY * sensitivity)

                ballX = nextX.coerceIn(ballRadiusPx, maxX)
                ballY = nextY.coerceIn(ballRadiusPx, maxY)
            }
        }
    }

    // Shield pulse animation for Proximity NEAR state
    val infiniteTransition = rememberInfiniteTransition(label = "shieldPulse")
    val shieldScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Header Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Multi-Sensor Lab",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryTextColor
                    )
                    Text(
                        text = if (isNightMode) "Tema Gelap (Cahaya Terang / Disenterin)" else "Tema Terang (Kondisi Gelap)",
                        fontSize = 12.sp,
                        color = subTextColor
                    )
                }

                // Lifecycle Status Indicator
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isLifecycleResumed) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (isLifecycleResumed) Color(0xFF10B981) else Color(0xFFEF4444),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isLifecycleResumed) "onResume" else "onPause",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isLifecycleResumed) Color(0xFF059669) else Color(0xFFDC2626)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. MOTION & POSITION CANVAS (Interactive Ball Area)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackgroundColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            val newSize = coordinates.size
                            if (containerSize != newSize) {
                                containerSize = newSize
                                if (ballX == 0f && ballY == 0f) {
                                    ballX = newSize.width / 2f
                                    ballY = newSize.height / 2f
                                }
                            }
                        }
                ) {
                    // Target concentric circles background
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val ringColor = if (isNightMode) Color(0xFF334155) else Color(0xFFE2E8F0)

                        drawCircle(color = ringColor, radius = 90.dp.toPx(), center = Offset(centerX, centerY), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                        drawCircle(color = ringColor, radius = 55.dp.toPx(), center = Offset(centerX, centerY), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                        drawCircle(color = ringColor, radius = 20.dp.toPx(), center = Offset(centerX, centerY), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                    }

                    // Interactive Ball
                    Box(
                        modifier = Modifier
                            .size(ballSize)
                            .graphicsLayer {
                                translationX = ballX - ballRadiusPx
                                translationY = ballY - ballRadiusPx
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Proximity Shield Ring (Position Sensor Effect)
                        if (effectiveIsNear) {
                            Box(
                                modifier = Modifier
                                    .size(ballSize * 1.6f)
                                    .scale(shieldScale)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEC4899).copy(alpha = 0.35f))
                                    .border(2.dp, Color(0xFFF43F5E), CircleShape)
                            )
                        }

                        // Ball Ambient Glow
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(10.dp)
                                .background(accentGlowColor.copy(alpha = 0.5f), CircleShape)
                        )

                        // Main Ball Body
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color.White,
                                            accentGlowColor,
                                            if (isNightMode) Color(0xFF1E1B4B) else Color(0xFF1E3A8A)
                                        )
                                    )
                                )
                                .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                        )
                    }

                    // Proximity Status Banner overlay on Canvas
                    if (effectiveIsNear) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFFF43F5E)
                        ) {
                            Text(
                                text = "🛡️ PROXIMITY SHIELD ACTIVE (OBJECT NEAR)",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = "Miringkan HP untuk menggerakkan bola • Dekatkan tangan untuk Shield",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                        color = subTextColor,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. LIVE SENSOR DASHBOARD
            Text(
                text = "Live Sensor Readings",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = primaryTextColor,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Card 1: Motion (Accelerometer)
                SensorCard(
                    modifier = Modifier.weight(1f),
                    title = "Motion",
                    subtitle = "Accelerometer",
                    icon = "📱",
                    valueText = "X: %.1f\nY: %.1f".format(accelX, accelY),
                    statusText = if (hasAccel) "Hardware Active" else "Not Detected",
                    cardBg = cardBackgroundColor,
                    primaryText = primaryTextColor,
                    subText = subTextColor
                )

                // Card 2: Environment (Light)
                SensorCard(
                    modifier = Modifier.weight(1f),
                    title = "Environment",
                    subtitle = "Light Sensor",
                    icon = if (isNightMode) "🌙" else "☀️",
                    valueText = "%.0f Lux".format(effectiveLux),
                    statusText = if (isNightMode) "Disenterin (Gelap)" else "Kondisi Redup",
                    cardBg = cardBackgroundColor,
                    primaryText = primaryTextColor,
                    subText = subTextColor
                )

                // Card 3: Position (Proximity)
                SensorCard(
                    modifier = Modifier.weight(1f),
                    title = "Position",
                    subtitle = "Proximity",
                    icon = if (effectiveIsNear) "✋" else "🖐️",
                    valueText = if (effectiveIsNear) "NEAR (%.0fcm)".format(rawProximityDistance) else "FAR (%.0fcm)".format(rawProximityDistance),
                    statusText = if (effectiveIsNear) "Shield ON" else "Clear",
                    cardBg = cardBackgroundColor,
                    primaryText = primaryTextColor,
                    subText = subTextColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. TESTING & SIMULATION PANEL (Fallback for Emulators)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cardBackgroundColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🛠️ Sensor Simulation & Testing Controls",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryTextColor
                    )
                    Text(
                        text = "Gunakan kontrol ini jika menguji di emulator tanpa sensor fisik.",
                        fontSize = 11.sp,
                        color = subTextColor
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = subTextColor.copy(alpha = 0.2f)
                    )

                    // Proximity Toggle & Hybrid Fallback
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Hybrid Proximity (Sensor Fisik + Tutup Senter/Cahaya)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = primaryTextColor
                                )
                                Text(
                                    text = "Aktif jika HP pakai Virtual Proximity (Cahaya < 15 Lux = NEAR)",
                                    fontSize = 10.sp,
                                    color = subTextColor
                                )
                            }

                            Switch(
                                checked = enableLightAsProximityFallback,
                                onCheckedChange = { enableLightAsProximityFallback = it }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Proximity Near Trigger",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = primaryTextColor
                                )
                                Text(
                                    text = if (useSimulatedProximity) "Menggunakan Simulasi" else "Sensor Fisik (Distance: %.1f cm)".format(rawProximityDistance),
                                    fontSize = 10.sp,
                                    color = subTextColor
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = useSimulatedProximity,
                                    onClick = { useSimulatedProximity = !useSimulatedProximity },
                                    label = { Text("Simulasi", fontSize = 10.sp) }
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Switch(
                                    checked = simulatedIsNear,
                                    onCheckedChange = {
                                        useSimulatedProximity = true
                                        simulatedIsNear = it
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Light Level Slider & Logic Toggle
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Mode Senter (Fisik Test)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = primaryTextColor
                                )
                                Text(
                                    text = if (invertLightLogic) "Disenterin -> Gelap, Redup -> Terang" else "Disenterin -> Terang, Redup -> Gelap",
                                    fontSize = 10.sp,
                                    color = subTextColor
                                )
                            }

                            Switch(
                                checked = invertLightLogic,
                                onCheckedChange = { invertLightLogic = it }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Ambient Light Level (Lux): %.0f".format(effectiveLux),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = primaryTextColor
                            )

                            Text(
                                text = if (isNightMode) "Tema Gelap" else "Tema Terang",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isNightMode) Color(0xFF38BDF8) else Color(0xFFEAB308)
                            )
                        }

                        Slider(
                            value = simulatedLux,
                            onValueChange = {
                                useSimulatedLight = true
                                simulatedLux = it
                            },
                            valueRange = 0f..1000f
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SensorCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: String,
    valueText: String,
    statusText: String,
    cardBg: Color,
    primaryText: Color,
    subText: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(text = icon, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = primaryText
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = subText
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = valueText,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = primaryText,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = statusText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = subText
            )
        }
    }
}
