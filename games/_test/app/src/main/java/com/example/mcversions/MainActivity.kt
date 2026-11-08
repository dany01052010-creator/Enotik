package com.example.mcversions

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Официальный манифест Mojang со всеми версиями Java Edition
private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

data class McVersion(val id: String, val type: String, val releaseTime: String)

suspend fun loadVersions(): List<McVersion> = withContext(Dispatchers.IO) {
    val conn = URL(MANIFEST).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    val text = conn.inputStream.bufferedReader().use { it.readText() }
    val arr = JSONObject(text).getJSONArray("versions")
    List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        McVersion(o.getString("id"), o.getString("type"), o.getString("releaseTime").take(10))
    }
}

val TYPES = listOf("all" to "Все", "release" to "Релизы", "snapshot" to "Снапшоты",
    "old_beta" to "Беты", "old_alpha" to "Альфы")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen() {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<McVersion>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("all") }

    LaunchedEffect(Unit) {
        try { all = loadVersions() } catch (e: Exception) { error = e.message ?: "Ошибка сети" }
        loading = false
    }

    val shown = all.filter {
        (type == "all" || it.type == type) && it.id.contains(query, ignoreCase = true)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Версии Minecraft (${shown.size})") }) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Поиск версии") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TYPES.forEach { (key, label) ->
                    FilterChip(selected = type == key, onClick = { type = key }, label = { Text(label) })
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null -> Text("Не удалось загрузить: $error")
                else -> LazyColumn {
                    items(shown, key = { it.id }) { v ->
                        ListItem(
                            headlineContent = { Text(v.id) },
                            supportingContent = { Text("${v.type} · ${v.releaseTime}") },
                            modifier = Modifier.clickable {
                                // Ссылка на официальную страницу вики с версией
                                val url = "https://minecraft.wiki/w/Java_Edition_${v.id}"
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
