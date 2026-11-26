package com.nouralwelayah.clock.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nouralwelayah.clock.data.MediaEntry
import com.nouralwelayah.clock.data.MediaLibrary
import com.nouralwelayah.clock.data.YouTubeSearchRepository
import com.nouralwelayah.clock.data.YouTubeVideoItem
import com.nouralwelayah.clock.media.MediaHub
import kotlinx.coroutines.launch

/**
 * In-app YouTube Search & Embedded Video Player section inside the Player Tab.
 *
 * Features:
 * - Zero-Key YouTube search via [YouTubeSearchRepository] (`youtubei/v1/search`)
 * - Bilingual Arabic (RTL) & English (LTR) search bar with placeholder
 *   `"Search YouTube... / ابحث في يوتيوب..."`, Search button (`ImeAction.Search`), and Clear (`X`) button
 * - Loading spinner, empty-state prompt before searching, and retryable error banner
 * - Embedded player container at the top of the section with Close/Minimize and in-app Fullscreen toggle
 * - Audio focus integration: pauses active local audio/recitation playback when a YouTube video starts
 */
@Composable
fun YouTubePlayerSection(
    volume: Float,
    savedLinks: List<MediaEntry>,
    onAddLinkClick: () -> Unit,
    onBeforeStartYouTube: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ar = isArabic()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var query by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<YouTubeVideoItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val activeVideo by MediaHub.embeddedYouTubeVideo.collectAsState()
    val isFullscreen by MediaHub.embeddedYouTubeFullscreen.collectAsState()

    fun executeSearch() {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || isSearching) return
        keyboard?.hide()
        focusManager.clearFocus()
        isSearching = true
        hasSearched = true
        errorMessage = null
        scope.launch {
            val lang = if (ar) "ar" else "en"
            val result = YouTubeSearchRepository.searchVideos(trimmed, lang)
            result.onSuccess { list ->
                searchResults = list
                errorMessage = null
            }.onFailure { err ->
                errorMessage = if (ar) {
                    "تعذّر الاتصال بيوتيوب (${err.message ?: "تحقق من اتصال Wi-Fi"})."
                } else {
                    "Failed to search YouTube (${err.message ?: "Check Wi-Fi connection"})."
                }
            }
            isSearching = false
        }
    }

    fun selectAndPlayVideo(item: YouTubeVideoItem) {
        keyboard?.hide()
        focusManager.clearFocus()
        // Pause any local MediaController audio/recitation playback immediately
        onBeforeStartYouTube()
        MediaHub.youTube.value = null
        MediaHub.embeddedYouTubeVideo.value = item
        MediaHub.youTubePlaying.value = true
    }

    fun playSavedEntry(entry: MediaEntry) {
        val vid = entry.youTubeId.ifBlank { MediaLibrary.youTubeId(entry.id).orEmpty() }
        if (vid.isBlank()) return
        selectAndPlayVideo(
            YouTubeVideoItem(
                videoId = vid,
                title = entry.title.ifBlank { "YouTube ($vid)" },
                channelName = "",
                durationText = "",
                thumbnailUrl = "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
            )
        )
    }

    // In-app fullscreen dialog overlay (stays strictly inside MainActivity / LockTask Kiosk Mode)
    val currentVideo = activeVideo
    if (currentVideo != null && isFullscreen) {
        EmbeddedYouTubeFullscreenDialog(
            onDismiss = { MediaHub.embeddedYouTubeFullscreen.value = false },
            content = {
                EmbeddedYouTubePlayer(
                    video = currentVideo,
                    volume = volume,
                    isFullscreen = true,
                    onToggleFullscreen = {
                        MediaHub.embeddedYouTubeFullscreen.value = false
                    },
                    onClose = {
                        MediaHub.embeddedYouTubeFullscreen.value = false
                        MediaHub.embeddedYouTubeVideo.value = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. Search Bar with Placeholder, Clear (X) button, and Search action button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Search YouTube... / ابحث في يوتيوب...",
                        color = C_DIM,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty() || searchResults.isNotEmpty() || errorMessage != null) {
                        IconButton(
                            onClick = {
                                query = ""
                                searchResults = emptyList()
                                errorMessage = null
                                hasSearched = false
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("youtube_search_clear_button"),
                        ) {
                            Text(
                                text = "✕",
                                color = C_TEXT,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { executeSearch() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("youtube_search_input"),
            )

            Button(
                onClick = { executeSearch() },
                enabled = query.trim().isNotEmpty() && !isSearching,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935),
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .height(54.dp)
                    .testTag("youtube_search_button"),
            ) {
                Text(
                    text = if (isSearching) {
                        tr("Searching...", "جارٍ البحث...")
                    } else {
                        tr("Search / بحث", "بحث / Search")
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 2. Embedded Player Card at the top of the Player Tab when a video is active (and not in fullscreen dialog)
        if (currentVideo != null && !isFullscreen) {
            EmbeddedYouTubePlayer(
                video = currentVideo,
                volume = volume,
                isFullscreen = false,
                onToggleFullscreen = {
                    MediaHub.embeddedYouTubeFullscreen.value = true
                },
                onClose = {
                    MediaHub.embeddedYouTubeFullscreen.value = false
                    MediaHub.embeddedYouTubeVideo.value = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 3. States & Results List
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("youtube_results_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Loading State
            if (isSearching) {
                item(key = "loading_state") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(C_CARD, RoundedCornerShape(10.dp))
                            .padding(20.dp)
                            .testTag("youtube_loading_indicator"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            color = C_GOLD,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = tr(
                                "Searching YouTube... / جارٍ البحث في يوتيوب...",
                                "جارٍ البحث في يوتيوب... / Searching YouTube..."
                            ),
                            color = C_TEXT,
                            fontSize = 16.sp,
                        )
                    }
                }
            }

            // Retryable Error Banner
            val err = errorMessage
            if (!isSearching && err != null) {
                item(key = "error_banner") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2B1216), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFFE53935), RoundedCornerShape(10.dp))
                            .padding(14.dp)
                            .testTag("youtube_error_banner"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr("Connection Error / خطأ في الاتصال", "خطأ في الاتصال / Connection Error"),
                                color = C_GOLD,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = err,
                                color = C_TEXT,
                                fontSize = 13.sp,
                            )
                        }
                        Button(
                            onClick = { executeSearch() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE53935),
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("youtube_retry_button"),
                        ) {
                            Text(
                                text = tr("Retry", "إعادة المحاولة"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // Empty-state prompt before searching
            if (!isSearching && !hasSearched && searchResults.isEmpty() && err == null) {
                item(key = "empty_prompt") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(C_CARD, RoundedCornerShape(10.dp))
                            .padding(18.dp)
                            .testTag("youtube_empty_state"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = tr(
                                "Search YouTube for Quran recitations, Duas, lectures, or Islamic programs",
                                "ابحث في يوتيوب عن تلاوات القرآن الكريم، الأدعية، الزيارات، أو المحاضرات الإسلامية"
                            ),
                            color = C_TEXT,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = tr(
                                "Type a search term above and press Search to watch directly inside the clock.",
                                "اكتب كلمة البحث في الأعلى واضغط بحث للمشاهدة مباشرة داخل الساعة."
                            ),
                            color = C_DIM,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            // No results found after searching
            if (!isSearching && hasSearched && searchResults.isEmpty() && err == null) {
                item(key = "no_results") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(C_CARD, RoundedCornerShape(10.dp))
                            .padding(18.dp)
                            .testTag("youtube_no_results_state"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tr(
                                "No playable YouTube videos found. Try another search query.",
                                "لم يتم العثور على مقاطع فيديو قابلة للتشغيل. جرّب البحث بكلمات أخرى."
                            ),
                            color = C_GOLD,
                            fontSize = 15.sp,
                        )
                    }
                }
            }

            // Search Results Items
            items(searchResults, key = { "yt_item_${it.videoId}" }) { item ->
                val isSelected = currentVideo?.videoId == item.videoId
                YouTubeVideoResultCard(
                    item = item,
                    isSelected = isSelected,
                    onClick = { selectAndPlayVideo(item) },
                )
            }

            // Saved YouTube Links & Add Link Button
            item(key = "saved_links_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = tr("Saved YouTube Links / الروابط المحفوظة", "الروابط المحفوظة / Saved YouTube Links"),
                        color = C_DIM,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedButton(
                        onClick = onAddLinkClick,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("youtube_add_link_button"),
                    ) {
                        Text(
                            text = tr("+ YouTube link", "+ رابط يوتيوب"),
                            color = C_GOLD,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            items(savedLinks, key = { "saved_${it.id}" }) { entry ->
                val entryVid = entry.youTubeId.ifBlank { MediaLibrary.youTubeId(entry.id).orEmpty() }
                val isPlayingSaved = currentVideo?.videoId == entryVid && entryVid.isNotBlank()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isPlayingSaved) C_ACCENT else C_CARD,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { playSavedEntry(entry) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "▶  ${entry.title}",
                        color = if (isPlayingSaved) Color.White else C_TEXT,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTubeVideoResultCard(
    item: YouTubeVideoItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) Color(0xFF1A334A) else C_CARD,
                RoundedCornerShape(10.dp),
            )
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = if (isSelected) C_GOLD else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
            .testTag("youtube_result_item_${item.videoId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 16:9 Thumbnail with Duration Badge
        Box(
            modifier = Modifier
                .size(width = 144.dp, height = 81.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            NetImage(
                url = item.thumbnailUrl,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.durationText.isNotBlank()) {
                Text(
                    text = item.durationText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }

        // Video Title (max 2 lines) & Channel Name
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                color = if (isSelected) C_GOLD else C_TEXT,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.channelName.isNotBlank()) {
                Text(
                    text = item.channelName,
                    color = C_DIM,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
