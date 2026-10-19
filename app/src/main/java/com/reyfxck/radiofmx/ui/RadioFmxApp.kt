package com.reyfxck.radiofmx.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.reyfxck.radiofmx.audio.AudioRoute
import com.reyfxck.radiofmx.audio.detectAudioRoute
import com.reyfxck.radiofmx.fm.FmRadioBridge
import com.reyfxck.radiofmx.fm.LineageFmBackend
import com.reyfxck.radiofmx.fm.LineageFmState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MinFm = 87.5f
private const val MaxFm = 108.0f
private const val BandSteps = 205

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RadioFmxApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("radiofmx", Context.MODE_PRIVATE) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var leftDrawerOpen by rememberSaveable { mutableStateOf(false) }
    var rightDrawerOpen by rememberSaveable { mutableStateOf(false) }

    var uiPoweredOn by rememberSaveable { mutableStateOf(prefs.getBoolean("powered_on", false)) }
    var simulatedScanning by rememberSaveable { mutableStateOf(false) }
    var frequency by rememberSaveable {
        mutableFloatStateOf(prefs.getFloat("frequency", 95.1f).coerceIn(MinFm, MaxFm))
    }

    val favorites = remember {
        mutableStateListOf<Float>().apply {
            addAll(
                prefs.getStringSet("favorites", emptySet())
                    .orEmpty()
                    .mapNotNull(String::toFloatOrNull)
                    .sorted()
            )
        }
    }

    fun setFrequency(value: Float, persist: Boolean = true) {
        frequency = quantize(value)
        if (persist) prefs.edit().putFloat("frequency", frequency).apply()
    }

    fun persistFavorites() {
        prefs.edit()
            .putStringSet("favorites", favorites.map { formatFrequency(it) }.toSet())
            .apply()
    }

    fun toggleFavorite(value: Float) {
        val tuned = quantize(value)
        val existing = favorites.indexOfFirst { abs(it - tuned) < 0.05f }
        if (existing >= 0) {
            favorites.removeAt(existing)
        } else {
            favorites.add(tuned)
            favorites.sort()
        }
        persistFavorites()
    }

    val oemFmPackage = remember { FmRadioBridge.findAvailablePackage(context) }

    val lineageBackend = remember { LineageFmBackend(context) }
    val fmState by lineageBackend.state.collectAsState()

    DisposableEffect(lineageBackend) {
        lineageBackend.connect()
        onDispose { lineageBackend.disconnect() }
    }

    LaunchedEffect(fmState.connected, fmState.powered) {
        while (fmState.connected) {
            lineageBackend.refresh()
            delay(if (fmState.powered) 900 else 1800)
        }
    }

    val poweredOn = if (fmState.connected) fmState.powered else uiPoweredOn
    val scanning = if (fmState.connected) fmState.scanning else simulatedScanning

    LaunchedEffect(uiPoweredOn) {
        prefs.edit().putBoolean("powered_on", uiPoweredOn).apply()
    }

    LaunchedEffect(simulatedScanning) {
        if (!simulatedScanning || fmState.connected) return@LaunchedEffect

        val start = frequency
        var cursor = MinFm
        repeat(BandSteps + 1) {
            if (!simulatedScanning || fmState.connected) return@LaunchedEffect
            frequency = quantize(cursor)
            cursor += 0.1f
            delay(42)
        }

        setFrequency(start)
        simulatedScanning = false
        snackbar.showSnackbar(
            "Varredura terminou. Sem backend FM acessível, não dá para medir sinal real."
        )
    }

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var audioRoute by remember { mutableStateOf(detectAudioRoute(audioManager)) }

    fun setVolume(value: Int) {
        val safe = value.coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, safe, 0)
        volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    LaunchedEffect(audioManager) {
        while (true) {
            val systemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (systemVolume != volume) volume = systemVolume

            val currentRoute = detectAudioRoute(audioManager)
            if (currentRoute != audioRoute) audioRoute = currentRoute

            delay(180)
        }
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            val attrs = Media3AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            setAudioAttributes(attrs, true)
            setHandleAudioBecomingNoisy(true)
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var localUri by rememberSaveable { mutableStateOf(prefs.getString("local_uri", null)) }
    var localTitle by rememberSaveable {
        mutableStateOf(
            localUri?.let { runCatching { displayName(context, Uri.parse(it)) }.getOrNull() }
        )
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(localUri) {
        val uri = localUri?.let(Uri::parse) ?: return@LaunchedEffect
        runCatching {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }

    LaunchedEffect(poweredOn) {
        if (!poweredOn) player.pause()
    }

    val openAudio = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            localUri = uri.toString()
            localTitle = displayName(context, uri)
            prefs.edit().putString("local_uri", localUri).apply()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }

    fun tuneTo(value: Float) {
        simulatedScanning = false
        setFrequency(value)
        if (fmState.connected && poweredOn) {
            lineageBackend.tune(frequency)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                Header(
                    poweredOn = poweredOn,
                    onStations = {
                        rightDrawerOpen = false
                        leftDrawerOpen = true
                    },
                    onDebug = {
                        leftDrawerOpen = false
                        rightDrawerOpen = true
                    },
                )

                FrequencyCard(
                    frequency = frequency,
                    poweredOn = poweredOn,
                    scanning = scanning,
                    hardwareBackend = fmState.connected,
                    isFavorite = favorites.any { abs(it - frequency) < 0.05f },
                    onPower = {
                        if (fmState.connected) {
                            lineageBackend.setPower(!fmState.powered, frequency)
                        } else {
                            uiPoweredOn = !uiPoweredOn
                            if (!uiPoweredOn) simulatedScanning = false
                        }
                    },
                    onFrequencyChange = { setFrequency(it, persist = false) },
                    onFrequencyChangeFinished = {
                        setFrequency(frequency)
                        if (fmState.connected && poweredOn) lineageBackend.tune(frequency)
                    },
                    onPrevious = {
                        tuneTo(if (frequency <= MinFm) MaxFm else frequency - 0.1f)
                    },
                    onNext = {
                        tuneTo(if (frequency >= MaxFm) MinFm else frequency + 0.1f)
                    },
                    onScan = {
                        if (fmState.connected) {
                            if (fmState.scanning) lineageBackend.cancelScan() else lineageBackend.scan()
                        } else {
                            simulatedScanning = !simulatedScanning
                        }
                    },
                    onFavorite = { toggleFavorite(frequency) },
                )

                if (favorites.isNotEmpty()) {
                    Text(
                        text = "Canais salvos",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        favorites.forEach { favorite ->
                            AssistChip(
                                onClick = { tuneTo(favorite) },
                                label = { Text("${formatFrequency(favorite)} MHz") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Favorite,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                    }
                }

                VolumeCard(
                    route = audioRoute,
                    volume = volume,
                    maxVolume = maxVolume,
                    fmBackendConnected = fmState.connected,
                    fmPowered = fmState.powered,
                    fmSpeakerEnabled = fmState.speakerEnabled,
                    onVolumeDown = { setVolume(volume - 1) },
                    onVolumeUp = { setVolume(volume + 1) },
                    onVolumeChange = { setVolume(it.roundToInt()) },
                    onSpeakerToggle = {
                        lineageBackend.setSpeaker(!fmState.speakerEnabled)
                    },
                )

                FmBackendCard(
                    fmState = fmState,
                    packageName = oemFmPackage,
                    frequency = frequency,
                    onReconnect = { lineageBackend.connect() },
                    onOpen = {
                        if (!FmRadioBridge.launch(context, frequency)) {
                            scope.launch {
                                snackbar.showSnackbar(
                                    "Nenhum app FM compatível pôde ser aberto."
                                )
                            }
                        }
                    },
                )

                LocalAudioCard(
                    title = localTitle,
                    isPlaying = isPlaying,
                    hasAudio = localUri != null,
                    poweredOn = poweredOn,
                    onChoose = { openAudio.launch(arrayOf("audio/*")) },
                    onPlayPause = {
                        if (player.mediaItemCount == 0 || !poweredOn) return@LocalAudioCard
                        if (player.isPlaying) player.pause() else player.play()
                    },
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.WifiOff, contentDescription = null)
                        Column {
                            Text("Offline de verdade", fontWeight = FontWeight.Bold)
                            Text(
                                if (fmState.connected) {
                                    "Backend Lineage/CAF conectado: power, tune, scan e speaker podem controlar o tuner físico."
                                } else {
                                    "Sem INTERNET. Arraste da esquerda para canais e da direita para manutenção."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                fmState.lastError?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))
            }
        }

        if (!leftDrawerOpen && !rightDrawerOpen) {
            EdgeOpenZone(
                left = true,
                modifier = Modifier.align(Alignment.CenterStart),
                onOpen = {
                    rightDrawerOpen = false
                    leftDrawerOpen = true
                },
            )
            EdgeOpenZone(
                left = false,
                modifier = Modifier.align(Alignment.CenterEnd),
                onOpen = {
                    leftDrawerOpen = false
                    rightDrawerOpen = true
                },
            )
        }

        if (leftDrawerOpen || rightDrawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.48f))
                    .clickable {
                        leftDrawerOpen = false
                        rightDrawerOpen = false
                    }
            )
        }

        if (leftDrawerOpen) {
            StationDrawer(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(0.88f),
                currentFrequency = frequency,
                detected = fmState.stations,
                favorites = favorites,
                hardwareBackend = fmState.connected,
                onTune = {
                    tuneTo(it)
                    leftDrawerOpen = false
                },
                onFavorite = ::toggleFavorite,
                onClose = { leftDrawerOpen = false },
            )
        }

        if (rightDrawerOpen) {
            DebugDrawer(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.90f),
                fmState = fmState,
                oemPackage = oemFmPackage,
                route = audioRoute,
                volume = volume,
                maxVolume = maxVolume,
                currentFrequency = frequency,
                onRefresh = { lineageBackend.refresh() },
                onReconnect = { lineageBackend.connect() },
                onOpenFm = { FmRadioBridge.launch(context, frequency) },
                onClose = { rightDrawerOpen = false },
            )
        }
    }
}

@Composable
private fun EdgeOpenZone(
    left: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    var drag by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(24.dp)
            .pointerInput(left) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        drag += amount
                    },
                    onDragEnd = {
                        if ((left && drag > 55f) || (!left && drag < -55f)) onOpen()
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                .width(4.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        )
    }
}

@Composable
private fun Header(
    poweredOn: Boolean,
    onStations: () -> Unit,
    onDebug: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onStations) {
            Icon(Icons.Rounded.Menu, contentDescription = "Abrir rádios")
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "RadioFMX",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = if (poweredOn) "receiver ativo" else "receiver offline",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (poweredOn) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.Radio,
                contentDescription = null,
                modifier = Modifier.padding(11.dp).size(24.dp),
                tint = if (poweredOn) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        IconButton(onClick = onDebug) {
            Icon(Icons.Rounded.BugReport, contentDescription = "Abrir manutenção")
        }
    }
}

@Composable
private fun FrequencyCard(
    frequency: Float,
    poweredOn: Boolean,
    scanning: Boolean,
    hardwareBackend: Boolean,
    isFavorite: Boolean,
    onPower: () -> Unit,
    onFrequencyChange: (Float) -> Unit,
    onFrequencyChangeFinished: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onScan: () -> Unit,
    onFavorite: () -> Unit,
) {
    val cardColor by animateColorAsState(
        targetValue = if (poweredOn) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "receiver-card",
    )
    val powerScale by animateFloatAsState(
        targetValue = if (poweredOn) 1f else 0.9f,
        animationSpec = tween(300),
        label = "power-scale",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(38.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        if (hardwareBackend) "FM HARDWARE" else "FM RECEIVER",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        when {
                            scanning -> "PROCURANDO..."
                            poweredOn && hardwareBackend -> "ON AIR • TUNER"
                            poweredOn -> "ON AIR • UI"
                            else -> "DESLIGADO"
                        },
                        color = if (poweredOn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onFavorite,
                        enabled = poweredOn,
                    ) {
                        Icon(
                            imageVector = if (isFavorite) {
                                Icons.Rounded.Favorite
                            } else {
                                Icons.Rounded.FavoriteBorder
                            },
                            contentDescription = if (isFavorite) "Remover canal" else "Salvar canal",
                        )
                    }
                    FilledIconButton(
                        onClick = onPower,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = powerScale
                                scaleY = powerScale
                            }
                            .shadow(if (poweredOn) 12.dp else 0.dp, CircleShape),
                    ) {
                        Icon(
                            Icons.Rounded.PowerSettingsNew,
                            contentDescription = if (poweredOn) "Desligar" else "Ligar",
                        )
                    }
                }
            }

            SignalVisualizer(active = poweredOn, scanning = scanning)

            Text(
                text = formatFrequency(frequency),
                fontSize = 66.sp,
                lineHeight = 70.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-2).sp,
                modifier = Modifier.alpha(if (poweredOn) 1f else 0.45f),
            )
            Text(
                text = "MHz",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Slider(
                value = frequency,
                onValueChange = onFrequencyChange,
                onValueChangeFinished = onFrequencyChangeFinished,
                valueRange = MinFm..MaxFm,
                steps = 204,
                enabled = poweredOn && !scanning,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("87.5", style = MaterialTheme.typography.labelSmall)
                Text("108.0", style = MaterialTheme.typography.labelSmall)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onPrevious,
                    enabled = poweredOn && !scanning,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "0,1 MHz abaixo")
                    Text("0.1")
                }

                Button(
                    onClick = onScan,
                    enabled = poweredOn,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (scanning) "PARAR" else "SCAN")
                }

                FilledTonalButton(
                    onClick = onNext,
                    enabled = poweredOn && !scanning,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Text("0.1")
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "0,1 MHz acima")
                }
            }

            Text(
                text = if (hardwareBackend) {
                    "A frequência selecionada é enviada ao tuner físico."
                } else {
                    "Ligue o receiver e selecione a frequência. O hardware depende do backend da ROM."
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SignalVisualizer(
    active: Boolean,
    scanning: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "radio-signal")
    val a by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (scanning) 260 else 620), RepeatMode.Reverse),
        label = "signal-a",
    )
    val b by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (scanning) 330 else 760), RepeatMode.Reverse),
        label = "signal-b",
    )
    val c by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (scanning) 220 else 540), RepeatMode.Reverse),
        label = "signal-c",
    )
    val levels = if (active) listOf(a, b, c, b * 0.9f, a * 0.8f) else List(5) { 0.16f }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier
                .height(42.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            levels.forEach { level ->
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(26.dp)
                        .graphicsLayer { scaleY = level.coerceIn(0.12f, 1f) }
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        )
                )
            }
        }
    }
}

@Composable
private fun VolumeCard(
    route: AudioRoute,
    volume: Int,
    maxVolume: Int,
    fmBackendConnected: Boolean,
    fmPowered: Boolean,
    fmSpeakerEnabled: Boolean,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSpeakerToggle: () -> Unit,
) {
    val percent = if (maxVolume > 0) ((volume * 100f) / maxVolume).roundToInt() else 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        imageVector = if (route.headphones) Icons.Rounded.Headphones else Icons.Rounded.Speaker,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp).size(26.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Saída de áudio",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${route.label} • $percent%",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (fmBackendConnected) {
                        Text(
                            if (fmSpeakerEnabled) "FM → alto-falante" else "FM → fone/rota padrão",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onVolumeDown) {
                    Icon(Icons.Rounded.VolumeDown, contentDescription = "Abaixar volume")
                }
                Slider(
                    value = volume.toFloat(),
                    onValueChange = onVolumeChange,
                    valueRange = 0f..maxVolume.toFloat().coerceAtLeast(1f),
                    steps = (maxVolume - 1).coerceAtLeast(0),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onVolumeUp) {
                    Icon(Icons.Rounded.VolumeUp, contentDescription = "Aumentar volume")
                }
            }

            FilledTonalButton(
                onClick = onSpeakerToggle,
                enabled = fmBackendConnected && fmPowered,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Speaker, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (fmSpeakerEnabled) "Usar fone / rota padrão" else "Usar alto-falante")
            }
        }
    }
}

@Composable
private fun FmBackendCard(
    fmState: LineageFmState,
    packageName: String?,
    frequency: Float,
    onReconnect: () -> Unit,
    onOpen: () -> Unit,
) {
    val subtitle = when {
        fmState.connected ->
            "Lineage/CAF conectado. Controle direto de power, sintonia, scan e speaker disponível."
        fmState.available ->
            "A rádio Lineage/CAF foi encontrada, mas o serviço ainda não conectou."
        packageName != null ->
            "App FM detectado: $packageName. Sem Binder conhecido, o RadioFMX pode abrir o app da ROM."
        else ->
            "Nenhum backend FM conhecido foi detectado neste momento."
    }

    InfoCard(
        icon = Icons.Rounded.Radio,
        title = "Tuner FM físico",
        subtitle = subtitle,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onReconnect) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Reconectar")
            }
            Button(
                onClick = onOpen,
                enabled = packageName != null,
            ) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("${formatFrequency(frequency)}")
            }
        }
    }
}

@Composable
private fun LocalAudioCard(
    title: String?,
    isPlaying: Boolean,
    hasAudio: Boolean,
    poweredOn: Boolean,
    onChoose: () -> Unit,
    onPlayPause: () -> Unit,
) {
    InfoCard(
        icon = Icons.Rounded.FolderOpen,
        title = "Player local",
        subtitle = title
            ?: "Escolha um áudio para testar a rota, foco de mídia e volume sem internet.",
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onChoose) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (hasAudio) "Trocar" else "Escolher áudio")
            }
            Button(
                onClick = onPlayPause,
                enabled = hasAudio && poweredOn,
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isPlaying) "Pausar" else "Tocar")
            }
        }
    }
}

@Composable
private fun StationDrawer(
    modifier: Modifier,
    currentFrequency: Float,
    detected: List<Float>,
    favorites: List<Float>,
    hardwareBackend: Boolean,
    onTune: (Float) -> Unit,
    onFavorite: (Float) -> Unit,
    onClose: () -> Unit,
) {
    var closeDrag by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { closeDrag = 0f },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    closeDrag += amount
                },
                onDragEnd = {
                    if (closeDrag < -65f) onClose()
                    closeDrag = 0f
                },
                onDragCancel = { closeDrag = 0f },
            )
        },
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Rádios", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text(
                "← arraste para fechar",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Sintonizada", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${formatFrequency(currentFrequency)} MHz",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                    )
                }
            }

            Text("Encontradas pelo scan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (detected.isEmpty()) {
                Text(
                    if (hardwareBackend) {
                        "Nenhuma estação listada ainda. Ligue o tuner e rode SCAN."
                    } else {
                        "O backend físico ainda não está conectado; sem ele não dá para identificar estações reais."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                detected.forEach { station ->
                    ChannelRow(
                        frequency = station,
                        detail = "Detectada pelo tuner",
                        favorite = favorites.any { abs(it - station) < 0.05f },
                        onClick = { onTune(station) },
                        onFavorite = { onFavorite(station) },
                    )
                }
            }

            HorizontalDivider()

            Text("Favoritas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (favorites.isEmpty()) {
                Text("Nenhum canal salvo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                favorites.forEach { station ->
                    ChannelRow(
                        frequency = station,
                        detail = "Canal salvo",
                        favorite = true,
                        onClick = { onTune(station) },
                        onFavorite = { onFavorite(station) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    frequency: Float,
    detail: String,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${formatFrequency(frequency)} MHz",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (favorite) "Remover favorito" else "Favoritar",
                )
            }
        }
    }
}

@Composable
private fun DebugDrawer(
    modifier: Modifier,
    fmState: LineageFmState,
    oemPackage: String?,
    route: AudioRoute,
    volume: Int,
    maxVolume: Int,
    currentFrequency: Float,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onOpenFm: () -> Unit,
    onClose: () -> Unit,
) {
    var closeDrag by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { closeDrag = 0f },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    closeDrag += amount
                },
                onDragEnd = {
                    if (closeDrag > 65f) onClose()
                    closeDrag = 0f
                },
                onDragCancel = { closeDrag = 0f },
            )
        },
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Manutenção", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text(
                "arraste → para fechar",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )

            DebugValue("RadioFMX", "0.3.0")
            DebugValue("Android", "${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}")
            DebugValue("Dispositivo", "${Build.MANUFACTURER} ${Build.MODEL}")
            DebugValue("Frequência UI", "${formatFrequency(currentFrequency)} MHz")
            DebugValue("Saída Android", route.label)
            DebugValue("Volume", "$volume / $maxVolume")
            DebugValue("App FM detectado", oemPackage ?: "nenhum")
            DebugValue("CAF instalado", yesNo(fmState.available))
            DebugValue("Binder CAF", if (fmState.connected) "conectado" else "desconectado")
            DebugValue("FM ligado", yesNo(fmState.powered))
            DebugValue("Scan físico", yesNo(fmState.scanning))
            DebugValue("Speaker FM", yesNo(fmState.speakerEnabled))
            DebugValue("Antena disponível", yesNo(fmState.antennaAvailable))
            DebugValue("Fone visto pelo FM", yesNo(fmState.wiredHeadsetAvailable))
            DebugValue("RSSI", fmState.rssi?.toString() ?: "—")
            DebugValue("Estações encontradas", fmState.stations.size.toString())
            DebugValue("RDS PS", fmState.programService.ifBlank { "—" })
            DebugValue("RadioText", fmState.radioText.ifBlank { "—" })
            DebugValue("Internet", "permissão ausente")

            fmState.lastError?.let {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            FilledTonalButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Atualizar diagnóstico")
            }
            OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Reconectar backend FM")
            }
            Button(
                onClick = onOpenFm,
                enabled = oemPackage != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Abrir rádio da ROM")
            }
        }
    }
}

@Composable
private fun DebugValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp).size(26.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            actions()
        }
    }
}

private fun quantize(value: Float): Float =
    ((value.coerceIn(MinFm, MaxFm) * 10f).roundToInt() / 10f)

private fun formatFrequency(value: Float): String =
    String.format(Locale.US, "%.1f", value)

private fun yesNo(value: Boolean): String = if (value) "sim" else "não"

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return uri.lastPathSegment ?: "Áudio local"
}
