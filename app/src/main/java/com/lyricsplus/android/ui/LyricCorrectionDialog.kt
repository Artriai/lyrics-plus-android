package com.lyricsplus.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lyricsplus.android.LyricsUiState
import com.lyricsplus.android.data.SongSearchResult
import kotlin.math.abs

private val DialogBackground = Color(0xFF161A18)
private val CardBackground = Color(0xFF202422)
private val CardBorder = Color(0x26FFFFFF)
private val AccentColor = Color(0xFF4AD295)
private val TextMutedColor = Color(0x99FFFFFF)

@Composable
fun LyricCorrectionDialog(
    state: LyricsUiState,
    onQueryChange: (String) -> Unit,
    onSourceChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectSong: (SongSearchResult) -> Unit,
    onResetAutoMatch: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = DialogBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "歌词匹配纠错",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "当前: ${state.nowPlaying.track} - ${state.nowPlaying.artist}",
                            color = TextMutedColor,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0x22FFFFFF), CircleShape)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "✕", color = Color.White, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search Input Box
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBackground, RoundedCornerShape(12.dp))
                        .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "🔍", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))

                    BasicTextField(
                        value = state.correctionSearchQuery,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(AccentColor),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        decorationBox = { innerTextField ->
                            if (state.correctionSearchQuery.isEmpty()) {
                                Text(
                                    text = "输入歌曲名或歌手搜索...",
                                    color = TextMutedColor,
                                    fontSize = 14.sp
                                )
                            }
                            innerTextField()
                        }
                    )

                    if (state.correctionSearchQuery.isNotEmpty()) {
                        Text(
                            text = "✕",
                            color = TextMutedColor,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { onQueryChange("") }
                                .padding(horizontal = 6.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(AccentColor, RoundedCornerShape(8.dp))
                            .clickable { onSearch() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "搜索",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Platform Filter Chips
                val sources = listOf("全部", "网易云音乐", "QQ音乐", "LRCLIB")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sources.forEach { src ->
                        val isSelected = state.correctionSelectedSource == src
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isSelected) AccentColor else CardBackground,
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) AccentColor else CardBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onSourceChange(src) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = src,
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Results Container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (state.isCorrectionSearching) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = AccentColor, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = "正在搜索歌词库...", color = TextMutedColor, fontSize = 13.sp)
                        }
                    } else if (state.correctionSearchResults.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = state.correctionErrorMessage ?: "请输入关键词搜索歌曲",
                                color = TextMutedColor,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        val currentDuration = state.nowPlaying.durationSeconds.toLong()
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.correctionSearchResults) { song ->
                                SongSearchResultItem(
                                    song = song,
                                    expectedDuration = currentDuration,
                                    onClick = { onSelectSong(song) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = CardBorder)
                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0x26FF5555), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0x4DFF5555), RoundedCornerShape(8.dp))
                            .clickable { onResetAutoMatch() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "🔄 恢复自动匹配",
                            color = Color(0xFFFF8888),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(CardBackground, RoundedCornerShape(8.dp))
                            .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "关闭",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SongSearchResultItem(
    song: SongSearchResult,
    expectedDuration: Long,
    onClick: () -> Unit
) {
    val durationText = formatSeconds(song.durationSeconds)
    val diff = if (expectedDuration > 0 && song.durationSeconds > 0) {
        song.durationSeconds - expectedDuration
    } else 0L

    val diffText = if (expectedDuration > 0 && song.durationSeconds > 0) {
        if (diff == 0L) "时长完全一致"
        else if (diff > 0) "+${diff}s"
        else "${diff}s"
    } else ""

    val isCloseMatch = abs(diff) <= 3

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(10.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = song.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                // Source Badge
                Box(
                    modifier = Modifier
                        .background(
                            when (song.source) {
                                "网易云音乐" -> Color(0x33E60026)
                                "QQ音乐" -> Color(0x3331C27C)
                                else -> Color(0x334AD295)
                            },
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = song.source,
                        color = when (song.source) {
                            "网易云音乐" -> Color(0xFFFF6677)
                            "QQ音乐" -> Color(0xFF66E6AA)
                            else -> Color(0xFF88FFCC)
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val desc = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ")
            Text(
                text = desc,
                color = TextMutedColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Duration & Diff Column
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = durationText,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            if (diffText.isNotBlank()) {
                Text(
                    text = diffText,
                    color = if (isCloseMatch) AccentColor else TextMutedColor,
                    fontSize = 10.sp
                )
            }
        }
    }
}

private fun formatSeconds(seconds: Long): String {
    if (seconds <= 0) return "--:--"
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
