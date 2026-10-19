package com.reyfxck.radiofmx.ui

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Radio
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MinFm = 87.5f
private const val MaxFm = 108.0f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RadioFmxApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("radiofmx", Context.MODE_PRIVATE) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var poweredOn by rememberSaveable { mutableStateOf(prefs.getBoolean("powered_on", false)) }
    var scanning by rememberSaveable { mutableStateOf(false) }
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

    fun setFrequency(value: Float) {
        frequency = quantize(value)
        prefs.edit().putFloat("frequency", frequency).apply()
    }

    fun persistFavorites() {
        prefs.edit()
            .putStringSet("favorites", favorites.map { formatFrequency(it) }.toSet())
            .apply()
    }

    val oemFmPackage = remember { FmRadioBridge.findAvailablePackage(context) }

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

    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                audioRoute = detectAudioRoute(audioManager)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                audioRoute = detectAudioRoute(audioManager)
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        onDispose {
            audioManager.unregisterAudioDeviceCallback(callback)
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
        prefs.edit().putBoolean("powered_on", poweredOn).apply()
        if (!poweredOn) {
            scanning = false
            player.pause()
        }
    }

    LaunchedEffect(scanning, poweredOn) {
        if (!scanning || !poweredOn) return@LaunchedEffect
        while (scanning && poweredOn) {
            delay(85)
            setFrequency(if (frequency >= MaxFm) MinFm else frequency + 0.1f)
        }
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

            Header(poweredOn)

            FrequencyCard(
                frequency = frequency,
                poweredOn = poweredOn,
                scanning = scanning,
                isFavorite = favorites.any { abs(it - frequency) < 0.05f },
                onPower = { poweredOn = !poweredOn },
                onFrequencyChange = ::setFrequency,
                onPrevious = {
                    setFrequency(if (frequency <= MinFm) MaxFm else frequency - 0.1f)
                },
                onNext = {
                    setFrequency(if (frequency >= MaxFm) MinFm else frequency + 0.1f)
                },
                onScan = { scanning = !scanning },
                onFavorite = {
                    val existing = favorites.indexOfFirst { abs(it - frequency) < 0.05f }
                    if (existing >= 0) {
                        favorites.removeAt(existing)
                    } else {
                        favorites.add(frequency)
                        favorites.sort()
                    }
                    persistFavorites()
                },
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
                            onClick = {
                                scanning = false
                                setFrequency(favorite)
                            },
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
                onVolumeDown = { setVolume(volume - 1) },
                onVolumeUp = { setVolume(volume + 1) },
                onVolumeChange = { setVolume(it.roundToInt()) },
            )

            OemFmCard(
                packageName = oemFmPackage,
                frequency = frequency,
                onOpen = {
                    if (!FmRadioBridge.launch(context, frequency)) {
                        scope.launch {
                            snackbar.showSnackbar(
                                "Este Android não expôs um tuner FM compatível ao RadioFMX."
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
                            "Sem INTERNET. O scan percorre a banda, mas detectar sinal FM real exige um backend de tuner do fabricante.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Header(poweredOn: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
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
            shape = RoundedCornerShape(22.dp),
            color = if (poweredOn) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.Radio,
                contentDescription = null,
                modifier = Modifier.padding(14.dp).size(28.dp),
                tint = if (poweredOn) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun FrequencyCard(
    frequency: Float,
    poweredOn: Boolean,
    scanning: Boolean,
    isFavorite: Boolean,
    onPower: () -> Unit,
    onFrequencyChange: (Float) -> Unit,
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
                        "FM RECEIVER",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                    )
                    if (poweredOn) {
                        Text(
                            if (scanning) "PROCURANDO..." else "ON AIR",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black,
                        )
                    } else {
                        Text(
                            "DESLIGADO",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
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
                valueRange = MinFm..MaxFm,
                steps = 204,
                enabled = poweredOn,
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
                    enabled = poweredOn,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
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
                    enabled = poweredOn,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Text("0.1")
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "0,1 MHz acima")
                }
            }

            Text(
                text = if (poweredOn) {
                    "Sintonizado em ${formatFrequency(frequency)} MHz"
                } else {
                    "Ligue o receiver para sintonizar"
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
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeChange: (Float) -> Unit,
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
        }
    }
}

@Composable
private fun OemFmCard(
    packageName: String?,
    frequency: Float,
    onOpen: () -> Unit,
) {
    InfoCard(
        icon = Icons.Rounded.Radio,
        title = "Tuner FM físico",
        subtitle = if (packageName != null) {
            "Backend OEM detectado. O RadioFMX tentará entregar ${formatFrequency(frequency)} MHz ao app do fabricante."
        } else {
            "O sistema não expôs um app/tuner FM compatível. A interface sintoniza a banda, mas o chip físico continua bloqueado pelo fabricante."
        },
    ) {
        Button(onClick = onOpen, enabled = packageName != null) {
            Icon(Icons.Rounded.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Sintonizar no FM nativo")
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
