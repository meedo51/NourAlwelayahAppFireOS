package com.nouralwelayah.clock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nouralwelayah.clock.data.GeoPlace
import com.nouralwelayah.clock.data.OnlineGeo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** "Online": search a city on the internet and pick one (name, latitude, longitude, time zone). */
@Composable
fun OnlineCityDialog(initial: String, onDismiss: () -> Unit, onPick: (GeoPlace) -> Unit) {
    val scope = rememberCoroutineScope()
    val ar = isArabic()
    var q by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<GeoPlace>>(emptyList()) }

    fun go() {
        if (busy) return
        busy = true
        msg = if (ar) "جاري البحث..." else "Searching..."
        list = emptyList()
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { OnlineGeo.search(q) } }
            busy = false
            r.onSuccess {
                list = it
                msg = if (it.isEmpty()) {
                    if (ar) "لم يتم العثور على نتائج - جرب كتابة الاسم بطريقة أخرى" else "Nothing found - try another spelling"
                } else {
                    if (ar) "اختر المدينة الصحيحة:" else "Tap the right place:"
                }
            }
            r.onFailure { msg = it.message ?: if (ar) "فشل البحث" else "Search failed" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Find a city online", "البحث عن مدينة عبر الإنترنت")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        q,
                        { q = it },
                        singleLine = true,
                        label = { Text(tr("City name", "اسم المدينة")) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { go() }),
                    )
                    OutlinedButton(onClick = { go() }, enabled = !busy && q.isNotBlank()) {
                        Text(tr("Search", "بحث"))
                    }
                }
                if (msg.isNotEmpty()) Text(msg, color = if (list.isEmpty() && !busy) C_GOLD else C_DIM, fontSize = 14.sp)
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEach { p ->
                        Column(Modifier.fillMaxWidth().background(C_CARD, RoundedCornerShape(10.dp))
                            .clickable { onPick(p) }.padding(12.dp)) {
                            Text(p.label, color = C_TEXT)
                            Text(String.format(Locale.US, "%.4f, %.4f", p.lat, p.lon) + (if (p.zone.isNotEmpty()) "   ${p.zone}" else ""),
                                color = C_DIM, fontSize = 13.sp)
                        }
                    }
                }
                Text(
                    tr("Search by Open-Meteo (GeoNames data). Needs the internet.", "البحث عبر خدمة Open-Meteo (يتطلب اتصالاً بالإنترنت)."),
                    color = C_DIM,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}
