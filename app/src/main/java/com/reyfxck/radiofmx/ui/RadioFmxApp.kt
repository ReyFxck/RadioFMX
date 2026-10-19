package com.reyfxck.radiofmx.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.reyfxck.radiofmx.fm.FmRadioBridge
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

    var frequency by rememberSaveable { mutableFloatStateOf(99.5f) }
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

    fun persistFavorites() {
        prefs.edit()
            .putStringSet("favorites", favorites.map { formatFrequency(it) }.toSet())
            .apply()
    }

    val oemFmPackage = remember { FmRadioBridge.findAvailablePackage(context) }

    val player = remember { ExoPlayer.Builder(context).build() }
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

            Header()

            FrequencyCard(
                frequency = frequency,
                isFavorite = favorites.any { abs(it - frequency) < 0.05f },
                onFrequencyChange = { frequency = quantize(it) },
                onPrevious = { frequency = quantize((frequency - 0.1f).coerceAtLeast(MinFm)) },
                onNext = { frequency = quantize((frequency + 0.1f).coerceAtMost(MaxFm)) },
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
                    text = "Presets",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    favorites.forEach { favorite ->
                        AssistChip(
                            onClick = { frequency = favorite },
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

            OemFmCard(
                packageName = oemFmPackage,
                onOpen = {
                    if (!FmRadioBridge.launch(context)) {
                        scope.launch {
                            snackbar.showSnackbar(
                                "Nenhum app FM compatível foi encontrado neste aparelho."
                            )
                        }
                    }
                },
            )

            LocalAudioCard(
                title = localTitle,
                isPlaying = isPlaying,
                hasAudio = localUri != null,
                onChoose = { openAudio.launch(arrayOf("audio/*")) },
                onPlayPause = {
                    if (player.mediaItemCount == 0) return@LocalAudioCard
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
                        Text(
                            "100% offline nesta build",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "O manifesto não pede INTERNET. Rádio online entra depois como opção separada.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header() {
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
                text = "offline radio lab",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                imageVector = Icons.Rounded.Radio,
                contentDescription = null,
                modifier = Modifier.padding(14.dp).size(28.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun FrequencyCard(
    frequency: Float,
    isFavorite: Boolean,
    onFrequencyChange: (Float) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(36.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onFavorite) {
                    Icon(
                        imageVector = if (isFavorite) {
                            Icons.Rounded.Favorite
                        } else {
                            Icons.Rounded.FavoriteBorder
                        },
                        contentDescription = if (isFavorite) "Remover preset" else "Salvar preset",
                    )
                }
            }

            Text(
                text = formatFrequency(frequency),
                fontSize = 64.sp,
                lineHeight = 68.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-2).sp,
            )
            Text(
                text = "MHz",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
            )

            Slider(
                value = frequency,
                onValueChange = onFrequencyChange,
                valueRange = MinFm..MaxFm,
                steps = 204,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onPrevious,
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
                ) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "0,1 MHz abaixo")
                }
                FilledTonalButton(
                    onClick = onNext,
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
                ) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "0,1 MHz acima")
                }
            }

            Text(
                text = "O mostrador é um preset do RadioFMX. A sintonia física só pode ser entregue ao app FM do fabricante quando o aparelho expõe esse recurso.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun OemFmCard(
    packageName: String?,
    onOpen: () -> Unit,
) {
    InfoCard(
        icon = Icons.Rounded.Headphones,
        title = "FM do aparelho",
        subtitle = if (packageName != null) {
            "App FM nativo detectado: $packageName"
        } else {
            "Nenhum app FM conhecido foi detectado. Isso varia conforme o fabricante e o hardware."
        },
    ) {
        Button(onClick = onOpen) {
            Icon(Icons.Rounded.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Abrir FM nativo")
        }
    }
}

@Composable
private fun LocalAudioCard(
    title: String?,
    isPlaying: Boolean,
    hasAudio: Boolean,
    onChoose: () -> Unit,
    onPlayPause: () -> Unit,
) {
    InfoCard(
        icon = Icons.Rounded.FolderOpen,
        title = "Player local",
        subtitle = title ?: "Escolha uma música, gravação ou arquivo de áudio do aparelho para testar o player sem internet.",
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
                enabled = hasAudio,
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
                    color = MaterialTheme.colorScheme.tertiaryContainer,
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
