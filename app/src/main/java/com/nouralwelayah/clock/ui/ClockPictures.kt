package com.nouralwelayah.clock.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nouralwelayah.clock.alert.ActiveAlert
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.PictureLibrary
import com.nouralwelayah.clock.data.PictureLibrary.Kind
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.system.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Darkens the picture so the clock stays readable (firmware AZAN_BG_DIM = 40%). */
private const val PICTURE_DIM = 0.4f

/**
 * Resolves all valid wallpaper image files from both FileSystemManager (/NourAlwelayah/wallpaper)
 * and PictureLibrary, deduplicated by filename.
 */
fun listWallpaperFiles(c: Context): List<File> {
    val extWallDir = FileSystemManager.getSubdirectory(c, "wallpaper")
    val extFiles = extWallDir.listFiles()?.filter {
        it.isFile && !it.name.startsWith(".") && AppFolders.isImage(it)
    }.orEmpty()
    val libFiles = PictureLibrary.list(c, Kind.WALLPAPER)
    val byName = linkedMapOf<String, File>()
    (extFiles + libFiles).forEach { file ->
        byName.putIfAbsent(file.name.lowercase(), file)
    }
    return byName.values.sortedBy { it.name.lowercase() }
}

/**
 * Resolves the active wallpaper file for [s], or null if Default Solid Navy is active.
 */
fun resolveSelectedWallpaperFile(c: Context, s: AppSettings): File? {
    if (!s.clockPicture) return null
    val all = listWallpaperFiles(c)
    if (s.selectedWallpaper.isNotBlank()) {
        val exact = all.firstOrNull { it.name.equals(s.selectedWallpaper, ignoreCase = true) }
            ?: FileSystemManager.validatePath(c, "wallpaper/${s.selectedWallpaper}")?.takeIf { it.isFile }
        if (exact != null && exact.exists()) return exact
    }
    return null
}

/**
 * Which picture is behind the clock (tablet), like the firmware's [AZBG] / [CLKBG]:
 * during an azan (switch on) a new azan picture for each azan; otherwise the selected wallpaper
 * (when clockPicture is enabled), or null for the Default Solid Navy (#0C1823) theme.
 */
private object PictureChoice {
    val changed = mutableIntStateOf(0)          // +1 when pictures are added / removed / re-tied
    var wall: File? = null
    var azanAlert: ActiveAlert? = null
    var azanFile: File? = null

    fun choose(c: Context, s: AppSettings, alert: ActiveAlert?): File? {
        if (alert != null && alert.azan >= 0 && s.azanPicture) {
            if (alert !== azanAlert || azanFile?.exists() != true) {
                azanAlert = alert
                azanFile = PictureLibrary.pick(c, Kind.AZAN, alert.azan, azanFile)
            }
            azanFile?.let { return it }
        }
        if (!s.clockPicture) {
            wall = null
            return null
        }
        if (s.selectedWallpaper.isNotBlank()) {
            val selected = resolveSelectedWallpaperFile(c, s)
            wall = selected
            return selected
        }
        if (wall?.exists() != true) wall = PictureLibrary.pick(c, Kind.WALLPAPER, -1, null)
        return wall
    }
}

/** Pictures were added / removed outside the picture lists (folders set up): read them again. */
fun picturesChanged() { PictureChoice.changed.intValue++ }

/** The tablet's clock background picture (or nothing), filling the whole box. */
@Composable
fun ClockPicture(s: AppSettings, alert: ActiveAlert?) {
    val ctx = LocalContext.current
    val changed = PictureChoice.changed.intValue
    val file = remember(s.azanPicture, s.clockPicture, s.selectedWallpaper, alert, changed) {
        PictureChoice.choose(ctx, s, alert)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val bmp by produceState<ImageBitmap?>(null, file, w, h) {
            value = file?.let { withContext(Dispatchers.IO) { PictureLibrary.decode(it, w, h)?.asImageBitmap() } }
        }
        bmp?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = PICTURE_DIM)))
        }
    }
}

/** Settings > Clock pictures (tablet): Azan picture on/off toggle + compact Wallpaper Selector Card + Modal Gallery Picker. */
@Composable
fun ClockPicturesSection(s: AppSettings, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val changed = PictureChoice.changed.intValue
    val wallPics = remember(changed) { listWallpaperFiles(ctx) }
    var busy by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    var showWallpaperGallery by remember { mutableStateOf(false) }
    val openGalleryReq by com.nouralwelayah.clock.media.MediaHub.openWallpaperGallery.collectAsState()

    LaunchedEffect(openGalleryReq) {
        if (openGalleryReq) {
            showWallpaperGallery = true
            com.nouralwelayah.clock.media.MediaHub.openWallpaperGallery.value = false
        }
    }

    fun refresh() = picturesChanged()
    val addWall = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            var lastAdded: File? = null
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    PictureLibrary.add(ctx, Kind.WALLPAPER, uri)?.let { lastAdded = it }
                }
            }
            if (lastAdded != null) {
                Store.saveSettings(
                    Store.settings.value.copy(
                        clockPicture = true,
                        selectedWallpaper = lastAdded!!.name,
                    )
                )
            }
            busy = false
            refresh()
        }
    }

    val activeWallpaperFile = remember(s.clockPicture, s.selectedWallpaper, wallPics) {
        if (!s.clockPicture) null
        else if (s.selectedWallpaper.isNotBlank()) {
            wallPics.firstOrNull { it.name.equals(s.selectedWallpaper, ignoreCase = true) }
        } else {
            wallPics.firstOrNull()
        }
    }

    Section(tr("Clock pictures (tablet)", "خلفيات وصور الساعة"), modifier) {
        Hint(tr("Shown behind the clock, darkened so the text stays readable.", "تُعرض خلف الساعة مع تعتيم خفيف ليبقى النص واضحاً."))

        SwitchRow(tr("Azan picture (during the azan)", "صورة الأذان (أثناء الأذان)"), s.azanPicture) {
            Store.saveSettings(Store.settings.value.copy(azanPicture = it))
        }
        Hint(
            tr(
                "A new picture for each azan: one tied to that prayer if there is one, else one for all azans. Names with fajr / dhuhr / maghrib are tied to that prayer when added.",
                "صورة مخصصة لكل أذان حسب الصلاة المحددة، أو لجميع الصلوات."
            )
        )

        Spacer(Modifier.height(6.dp))

        // ---- Compact Wallpaper Selector Card ----
        WallpaperSelectorCard(
            activeFile = activeWallpaperFile,
            totalWallpapers = wallPics.size,
            onOpenGallery = { showWallpaperGallery = true },
            onResetDefault = {
                Store.saveSettings(
                    Store.settings.value.copy(
                        selectedWallpaper = "",
                        clockPicture = false,
                    )
                )
                refresh()
            },
        )

        if (busy) Hint(tr("Copying...", "جاري النسخ..."))
    }

    if (showWallpaperGallery) {
        WallpaperGalleryDialog(
            wallpapers = wallPics,
            activeFile = activeWallpaperFile,
            isDefaultNavy = activeWallpaperFile == null,
            busy = busy,
            onSelectDefaultNavy = {
                Store.saveSettings(
                    Store.settings.value.copy(
                        selectedWallpaper = "",
                        clockPicture = false,
                    )
                )
                refresh()
            },
            onSelectWallpaper = { file ->
                Store.saveSettings(
                    Store.settings.value.copy(
                        selectedWallpaper = file.name,
                        clockPicture = true,
                    )
                )
                refresh()
            },
            onAddWallpapers = { addWall.launch(arrayOf("image/*")) },
            onRequestDelete = { file -> toDelete = file },
            onDismiss = { showWallpaperGallery = false },
        )
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(tr("Delete picture?", "حذف الصورة؟")) },
            text = { Text(PictureLibrary.shownName(f)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val deletingActive = s.selectedWallpaper.equals(f.name, ignoreCase = true)
                        PictureLibrary.delete(f)
                        if (deletingActive) {
                            Store.saveSettings(
                                Store.settings.value.copy(
                                    selectedWallpaper = "",
                                    clockPicture = false,
                                )
                            )
                        }
                        toDelete = null
                        refresh()
                    }
                ) {
                    Text(tr("Delete", "حذف"))
                }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) {
                    Text(tr("Cancel", "إلغاء"))
                }
            },
        )
    }
}

/**
 * Compact Wallpaper Selector Card displayed in the Settings Tab instead of rendering all wallpapers inline.
 */
@Composable
private fun WallpaperSelectorCard(
    activeFile: File?,
    totalWallpapers: Int,
    onOpenGallery: () -> Unit,
    onResetDefault: () -> Unit,
) {
    val thumb by produceState<ImageBitmap?>(null, activeFile) {
        value = activeFile?.let {
            withContext(Dispatchers.IO) { PictureLibrary.decode(it, 240, 150)?.asImageBitmap() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0C1823), RoundedCornerShape(12.dp))
            .border(1.dp, C_GOLD.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 16:10 active background preview thumbnail
            Box(
                modifier = Modifier
                    .size(128.dp, 80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(C_BG)
                    .border(1.5.dp, C_GOLD, RoundedCornerShape(8.dp))
                    .clickable { onOpenGallery() },
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb!!,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("12:00", color = C_GOLD, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = tr("Solid Navy", "كحلي داكن"),
                            color = C_DIM,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // Active wallpaper title + status
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = tr("Clock Background Wallpaper", "خلفية الشاشة الرئيسية للساعة"),
                    color = C_GOLD,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = activeFile?.let { PictureLibrary.shownName(it) }
                        ?: tr("Default Navy Theme", "الخلفية الكحلية الافتراضية"),
                    color = C_TEXT,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (activeFile != null) {
                        tr(
                            "Custom wallpaper active • $totalWallpapers available in /wallpaper",
                            "خلفية مخصصة مفعّلة • يتوفر $totalWallpapers في مجلد الخلفيات"
                        )
                    } else {
                        tr(
                            "Built-in solid Navy (#0C1823) • $totalWallpapers wallpapers in gallery",
                            "اللون الكحلي الرسمي (#0C1823) • يتوفر $totalWallpapers خلفية في المعرض"
                        )
                    },
                    color = C_DIM,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (activeFile != null) {
                    OutlinedButton(onClick = onResetDefault) {
                        Text(tr("Default Navy", "الافتراضي"), color = C_GOLD)
                    }
                }
                Button(
                    onClick = onOpenGallery,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = C_GOLD,
                        contentColor = C_BG,
                    ),
                ) {
                    Text(
                        text = tr("Change Background", "تغيير الخلفية"),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * Modern 16:10 Modal Gallery Picker Dialog for selecting the built-in Default Solid Navy background
 * or any wallpaper image from /NourAlwelayah/wallpaper/.
 */
@Composable
private fun WallpaperGalleryDialog(
    wallpapers: List<File>,
    activeFile: File?,
    isDefaultNavy: Boolean,
    busy: Boolean,
    onSelectDefaultNavy: () -> Unit,
    onSelectWallpaper: (File) -> Unit,
    onAddWallpapers: () -> Unit,
    onRequestDelete: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val cfg = LocalConfiguration.current
    val dialogWidthFraction = if (cfg.smallestScreenWidthDp >= 600) 0.84f else 0.96f
    val dialogHeight = (cfg.screenHeightDp * 0.84f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(dialogWidthFraction)
                .height(dialogHeight)
                .background(C_CARD, RoundedCornerShape(18.dp))
                .border(1.5.dp, C_GOLD.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("Select Clock Background", "اختر خلفية الساعة"),
                        color = C_GOLD,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = tr(
                            "Choose the default Solid Navy theme or tap any 16:10 wallpaper from /NourAlwelayah/wallpaper/",
                            "اختر الخلفية الكحلية الافتراضية أو اضغط على أي صورة بنسبة 16:10 من مجلد الخلفيات"
                        ),
                        color = C_DIM,
                        fontSize = 14.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onAddWallpapers, enabled = !busy) {
                        Text(tr("+ Add Wallpaper", "+ إضافة خلفية"), color = C_GOLD)
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = C_GOLD,
                            contentColor = C_BG,
                        ),
                    ) {
                        Text(tr("Done", "تم"), fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 16:10 Gallery Grid
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Item 0: Built-in Default (Solid Navy)
                item(key = "__default_navy__") {
                    DefaultNavyGalleryCard(
                        selected = isDefaultNavy,
                        onClick = onSelectDefaultNavy,
                    )
                }

                // Items 1..N: Discovered wallpaper files
                items(wallpapers, key = { it.absolutePath }) { file ->
                    val isSelected = !isDefaultNavy && activeFile?.name?.equals(file.name, ignoreCase = true) == true
                    WallpaperGalleryItemCard(
                        file = file,
                        selected = isSelected,
                        onSelect = { onSelectWallpaper(file) },
                        onDelete = { onRequestDelete(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultNavyGalleryCard(
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) C_GOLD else Color(0x33FFFFFF)
    val borderWidth = if (selected) 2.5.dp else 1.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C1823))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(C_BG),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "12:00:00",
                    color = C_TEXT,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = tr("Default (Solid Navy)", "الافتراضي (الكحلي الداكن)"),
                    color = C_GOLD,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(28.dp)
                        .background(C_GOLD, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = C_BG, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) C_GOLD.copy(alpha = 0.18f) else C_CARD)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = tr("Default (Solid Navy)", "الافتراضي (الكحلي الداكن)"),
                color = if (selected) C_GOLD else C_TEXT,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    text = tr("Active", "مفعّل"),
                    color = C_GOLD,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun WallpaperGalleryItemCard(
    file: File,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumb by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            PictureLibrary.decode(file, 320, 200)?.asImageBitmap()
        }
    }
    val borderColor = if (selected) C_GOLD else Color(0x33FFFFFF)
    val borderWidth = if (selected) 2.5.dp else 1.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C1823))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(C_BG),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb!!,
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = "...",
                    color = C_DIM,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(28.dp)
                        .background(C_GOLD, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = C_BG, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) C_GOLD.copy(alpha = 0.18f) else C_CARD)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = PictureLibrary.shownName(file),
                color = if (selected) C_GOLD else C_TEXT,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text(tr("Delete", "حذف"), color = C_GOLD, fontSize = 12.sp)
            }
        }
    }
}

