package io.lightknight.omniread.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.lightknight.omniread.core.OmniAppContainer
import io.lightknight.omniread.data.model.MediaType
import io.lightknight.omniread.data.repository.ReadingStatsCalculator
import io.lightknight.omniread.ui.theme.UmeforLime
import io.lightknight.omniread.ui.theme.UmeforMint
import io.lightknight.omniread.ui.theme.UmeforTealDeep
import io.lightknight.omniread.ui.theme.UmeforTealRich
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(
    container: OmniAppContainer,
    onBack: () -> Unit
) {
    val sessions by container.libraryRepository.observeRecentStats(30).collectAsState(initial = emptyList())
    val allItems by container.libraryRepository.observeAllItems().collectAsState(initial = emptyList())

    val streak = remember(sessions) { ReadingStatsCalculator.calculateStreak(sessions) }
    val last7Days = remember(sessions) {
        val byDate = sessions.associateBy { it.dateKey }
        val today = LocalDate.now()
        (6 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong())
            val key = d.toString()
            val label = d.format(DateTimeFormatter.ofPattern("dd/MM"))
            Triple(label, byDate[key]?.readingSeconds ?: 0L, byDate[key]?.listeningSeconds ?: 0L)
        }
    }

    val totalReadMin7d = last7Days.sumOf { it.second } / 60L
    val totalListenMin7d = last7Days.sumOf { it.third } / 60L
    val totalChapters = sessions.sumOf { it.chaptersCompleted }
    val maxDaySec = last7Days.maxOfOrNull { it.second + it.third }?.coerceAtLeast(60L) ?: 60L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thống kê đọc & nghe") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(UmeforTealDeep, UmeforTealRich)))
                        .padding(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocalFireDepartment, null, tint = UmeforLime, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                "Chuỗi $streak ngày liên tiếp",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "$totalChapters chương/tập hoàn thành gần đây",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFD5E6DD)
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                            Text("$totalReadMin7d phút", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Đọc (7 ngày qua)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Headphones, null, tint = UmeforMint)
                            Text("$totalListenMin7d phút", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Nghe TTS/Audio (7 ngày)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Biểu đồ 7 ngày gần nhất", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(148.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            last7Days.forEach { (label, readSec, listenSec) ->
                                val readFrac = (readSec.toFloat() / maxDaySec.toFloat()).coerceIn(0f, 1f)
                                val listenFrac = (listenSec.toFloat() / maxDaySec.toFloat()).coerceIn(0f, 1f)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .height(112.dp)
                                            .width(18.dp),
                                        contentAlignment = Alignment.BottomCenter
                                    ) {
                                        Column(verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                                            if (listenSec > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(14.dp)
                                                        .height((listenFrac * 100f).dp.coerceAtLeast(4.dp))
                                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                                        .background(UmeforMint)
                                                )
                                            }
                                            if (readSec > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(14.dp)
                                                        .height((readFrac * 100f).dp.coerceAtLeast(4.dp))
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(UmeforLime)
                                                )
                                            }
                                            if (readSec == 0L && listenSec == 0L) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(14.dp)
                                                        .height(3.dp)
                                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(UmeforLime, RoundedCornerShape(3.dp)))
                                Spacer(Modifier.width(6.dp))
                                Text("Đọc", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(UmeforMint, RoundedCornerShape(3.dp)))
                                Spacer(Modifier.width(6.dp))
                                Text("Nghe", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            item {
                val novels = allItems.count { it.mediaType == MediaType.NOVEL }
                val comics = allItems.count { it.mediaType == MediaType.COMIC }
                val audios = allItems.count { it.mediaType == MediaType.AUDIOBOOK }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Tổng quan Thư viện (${allItems.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("• Truyện chữ: $novels bộ", style = MaterialTheme.typography.bodyMedium)
                        Text("• Truyện tranh: $comics bộ", style = MaterialTheme.typography.bodyMedium)
                        Text("• Sách nói: $audios bộ", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
