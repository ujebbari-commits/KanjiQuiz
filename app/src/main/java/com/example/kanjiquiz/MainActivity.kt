package com.example.kanjiquiz

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

// ============================================================
//  AnkiDroid 公式API（ContentProvider）— 読み取りのみ
// ============================================================
private const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
private val DECKS_URI: Uri = Uri.parse("content://com.ichi2.anki.flashcards/decks")
private val NOTES_URI: Uri = Uri.parse("content://com.ichi2.anki.flashcards/notes")

private const val TIME_ATTACK_SEC = 60f

// ============================================================
//  設定（端末に保存）
// ============================================================
enum class GameMode { NORMAL, SURVIVAL, TIME_ATTACK }
enum class Mode { QUIZ, FLASHCARD }

data class Settings(
    val count: Int = 10,
    val timeLimitSec: Int = 15,
    val autoAdvance: Boolean = true,
    val feedbackDeci: Int = 15,
    val flashcardDeci: Int = 30,
    val reverse: Boolean = false,
    val gameMode: GameMode = GameMode.NORMAL,
    val weakPriority: Boolean = true,
)

data class RoundConfig(
    val items: List<QuizItem>,
    val gameMode: GameMode,
    val reverse: Boolean,
    val timeLimitSec: Int,
    val autoAdvance: Boolean,
    val feedbackDeci: Int,
)

data class HistoryEntry(
    val timeMillis: Long, val deck: String,
    val score: Int, val correct: Int, val total: Int,
)

class Store(context: Context) {
    private val sp = context.getSharedPreferences("kanjiquiz", Context.MODE_PRIVATE)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun loadSettings(): Settings = Settings(
        count = sp.getInt("count", 10),
        timeLimitSec = sp.getInt("timeLimit", 15),
        autoAdvance = sp.getBoolean("autoAdvance", true),
        feedbackDeci = sp.getInt("feedbackDeci", 15),
        flashcardDeci = sp.getInt("flashcardDeci", 30),
        reverse = sp.getBoolean("reverse", false),
        gameMode = runCatching { GameMode.valueOf(sp.getString("gameMode", "NORMAL")!!) }
            .getOrDefault(GameMode.NORMAL),
        weakPriority = sp.getBoolean("weakPriority", true),
    )

    fun saveSettings(s: Settings) {
        sp.edit()
            .putInt("count", s.count)
            .putInt("timeLimit", s.timeLimitSec)
            .putBoolean("autoAdvance", s.autoAdvance)
            .putInt("feedbackDeci", s.feedbackDeci)
            .putInt("flashcardDeci", s.flashcardDeci)
            .putBoolean("reverse", s.reverse)
            .putString("gameMode", s.gameMode.name)
            .putBoolean("weakPriority", s.weakPriority)
            .apply()
    }

    fun loadHistory(): List<HistoryEntry> {
        val raw = sp.getString("history", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(o.getLong("t"), o.getString("d"),
                    o.getInt("s"), o.getInt("c"), o.getInt("n"))
            }
        }.getOrDefault(emptyList())
    }

    fun addHistory(e: HistoryEntry) {
        val list = (listOf(e) + loadHistory()).take(50)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("t", it.timeMillis); put("d", it.deck)
                put("s", it.score); put("c", it.correct); put("n", it.total)
            })
        }
        sp.edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory() = sp.edit().remove("history").apply()

    fun loadCardStats(): MutableMap<Long, IntArray> {
        val raw = sp.getString("cardStats", null) ?: return mutableMapOf()
        return runCatching {
            val o = JSONObject(raw)
            val map = mutableMapOf<Long, IntArray>()
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                map[k.toLong()] = intArrayOf(a.getInt(0), a.getInt(1))
            }
            map
        }.getOrDefault(mutableMapOf())
    }

    fun saveCardStats(map: Map<Long, IntArray>) {
        val o = JSONObject()
        map.forEach { (id, a) -> o.put(id.toString(), JSONArray().put(a[0]).put(a[1])) }
        sp.edit().putString("cardStats", o.toString()).apply()
    }

    fun currentStreak(): Int {
        val last = sp.getString("streakDate", null) ?: return 0
        val today = dayFmt.format(Date())
        val yest = dayFmt.format(Date(System.currentTimeMillis() - 86_400_000L))
        val c = sp.getInt("streakCount", 0)
        return if (last == today || last == yest) c else 0
    }

    fun bumpStreak(): Int {
        val today = dayFmt.format(Date())
        val last = sp.getString("streakDate", null)
        if (last == today) return sp.getInt("streakCount", 1)
        val yest = dayFmt.format(Date(System.currentTimeMillis() - 86_400_000L))
        val streak = if (last == yest) sp.getInt("streakCount", 0) + 1 else 1
        sp.edit().putString("streakDate", today).putInt("streakCount", streak).apply()
        return streak
    }
}

data class DeckInfo(val id: Long, val name: String)

data class QuizItem(
    val noteId: Long,
    val question: String,
    val displayAnswer: String,
    val accepted: List<String>,
)

data class AnswerLog(val item: QuizItem, val correct: Boolean, val input: String)

private fun cleanText(s: String): String =
    s.replace(Regex("\\[sound:[^\\]]*\\]"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(div|p|li|tr)>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("[ \\t]*\n[ \\t]*"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

private fun kataToHira(s: String): String =
    buildString { for (ch in s) append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch) }

private fun normalizeKana(s: String): String =
    kataToHira(s).filter { it in 'ぁ'..'ゖ' || it == 'ー' }

private fun parseAnswers(raw: String): List<String> =
    cleanText(raw).split(Regex("[、,，;；・/／\\s　\\n]+"))
        .map { normalizeKana(it) }.filter { it.isNotEmpty() }.distinct()

private fun joinFields(flds: List<String>, indices: List<Int>): String =
    indices.sorted().mapNotNull { flds.getOrNull(it)?.let(::cleanText) }
        .filter { it.isNotBlank() }.joinToString("\n")

private fun acceptedFrom(flds: List<String>, indices: List<Int>): List<String> =
    indices.flatMap { parseAnswers(flds.getOrNull(it) ?: "") }.distinct()

private fun loadDecks(resolver: ContentResolver): List<DeckInfo> {
    val decks = mutableListOf<DeckInfo>()
    resolver.query(DECKS_URI, null, null, null, null)?.use { c ->
        val idIdx = c.getColumnIndex("deck_id")
        val nameIdx = c.getColumnIndex("deck_name")
        if (idIdx < 0 || nameIdx < 0) return emptyList()
        while (c.moveToNext()) {
            val name = c.getString(nameIdx) ?: continue
            decks.add(DeckInfo(c.getLong(idIdx), name))
        }
    }
    return decks.sortedBy { it.name }
}

private fun loadNotes(resolver: ContentResolver, deckName: String): List<Pair<Long, List<String>>> {
    val safeName = deckName.replace("\"", "")
    val notes = mutableListOf<Pair<Long, List<String>>>()
    resolver.query(NOTES_URI, null, "deck:\"$safeName\"", null, null)?.use { c ->
        val fldsIdx = c.getColumnIndex("flds")
        val idIdx = c.getColumnIndex("_id")
        if (fldsIdx < 0) return emptyList()
        while (c.moveToNext()) {
            val flds = c.getString(fldsIdx) ?: continue
            val id = if (idIdx >= 0) c.getLong(idIdx) else notes.size.toLong()
            notes.add(id to flds.split('\u001f'))
        }
    }
    return notes
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private enum class Stage { DECKS, FIELDS, QUIZ, FLASHCARD, RESULT, HISTORY, SETTINGS }

private val CorrectGreen = Color(0xFF81C784)
private val WrongRed = Color(0xFFE57373)
private val ComboOrange = Color(0xFFFFB74D)

@Composable
private fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store(context) }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, ANKI_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    var settings by remember { mutableStateOf(store.loadSettings()) }
    var stage by remember { mutableStateOf(Stage.DECKS) }

    var deckReload by remember { mutableIntStateOf(0) }
    var decks by remember { mutableStateOf<List<DeckInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var deckName by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<Pair<Long, List<String>>>>(emptyList()) }
    val qFields = remember { mutableStateListOf<Int>() }
    val aFields = remember { mutableStateListOf<Int>() }
    var mode by remember { mutableStateOf(Mode.QUIZ) }
    var fieldError by remember { mutableStateOf<String?>(null) }

    var config by remember { mutableStateOf<RoundConfig?>(null) }
    var flashItems by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var round by remember { mutableIntStateOf(0) }
    var lastLogs by remember { mutableStateOf<List<AnswerLog>>(emptyList()) }
    var lastScore by remember { mutableIntStateOf(0) }

    fun weightedOrder(list: List<QuizItem>): List<QuizItem> {
        val stats = store.loadCardStats()
        return list.map { item ->
            val wrong = stats[item.noteId]?.getOrNull(1) ?: 0
            val w = 1.0 + wrong
            val u = Random.nextDouble().coerceIn(1e-9, 1.0)
            item to u.pow(1.0 / w)
        }.sortedByDescending { it.second }.map { it.first }
    }

    fun buildRoundItems(): List<QuizItem> {
        val base = notes.map { (id, flds) ->
            QuizItem(id, joinFields(flds, qFields), joinFields(flds, aFields),
                acceptedFrom(flds, aFields))
        }
        val usable = base.filter {
            if (settings.reverse) it.question.isNotBlank() && it.displayAnswer.isNotBlank()
            else it.question.isNotBlank() && it.accepted.isNotEmpty()
        }
        val ordered = if (settings.weakPriority) weightedOrder(usable) else usable.shuffled()
        return if (settings.gameMode == GameMode.NORMAL && settings.count >= 0)
            ordered.take(settings.count) else ordered
    }

    fun buildFlash(): List<Pair<String, String>> {
        val list = notes.shuffled().mapNotNull { (_, flds) ->
            val q = joinFields(flds, qFields); val a = joinFields(flds, aFields)
            if (q.isBlank() && a.isBlank()) null else q to a
        }
        return if (settings.count < 0) list else list.take(settings.count)
    }

    fun recordAndFinish(logs: List<AnswerLog>, score: Int) {
        val stats = store.loadCardStats()
        logs.forEach { l ->
            val cur = stats[l.item.noteId] ?: intArrayOf(0, 0)
            cur[0] += 1
            cur[1] = if (l.correct) maxOf(0, cur[1] - 1) else minOf(10, cur[1] + 2)
            stats[l.item.noteId] = cur
        }
        store.saveCardStats(stats)
        store.bumpStreak()
        store.addHistory(
            HistoryEntry(System.currentTimeMillis(), deckName, score,
                logs.count { it.correct }, logs.size)
        )
        lastLogs = logs; lastScore = score; stage = Stage.RESULT
    }

    if (!granted) {
        PermissionScreen(onRequest = { permissionLauncher.launch(ANKI_PERMISSION) })
        return
    }

    when (stage) {
        Stage.DECKS -> {
            LaunchedEffect(deckReload) {
                loadError = null
                withContext(Dispatchers.IO) { runCatching { loadDecks(context.contentResolver) } }
                    .onSuccess { decks = it }
                    .onFailure {
                        loadError = "AnkiDroidに接続できませんでした。\n" +
                            "・AnkiDroidを一度起動したか\n" +
                            "・設定 → 高度な設定 → 「AnkiDroid API」が有効か\n" +
                            "を確認してください。"
                    }
            }
            DeckScreen(
                decks = decks, error = loadError, streak = store.currentStreak(),
                onRetry = { decks = null; deckReload++ },
                onHistory = { stage = Stage.HISTORY },
                onSettings = { stage = Stage.SETTINGS },
                onSelect = { deck ->
                    scope.launch {
                        loadError = null
                        withContext(Dispatchers.IO) {
                            runCatching { loadNotes(context.contentResolver, deck.name) }
                        }.onSuccess { loaded ->
                            if (loaded.isEmpty()) loadError = "「${deck.name}」からノートを取得できませんでした。"
                            else {
                                deckName = deck.name; notes = loaded
                                qFields.clear(); qFields.add(0)
                                aFields.clear(); aFields.add(if (loaded.first().second.size > 1) 1 else 0)
                                mode = Mode.QUIZ; fieldError = null; stage = Stage.FIELDS
                            }
                        }.onFailure { loadError = "ノートの取得に失敗しました: ${it.message}" }
                    }
                },
            )
        }

        Stage.SETTINGS -> SettingsScreen(
            settings = settings,
            onChange = { settings = it; store.saveSettings(it) },
            onBack = { stage = Stage.DECKS },
        )

        Stage.HISTORY -> HistoryScreen(
            entries = store.loadHistory(),
            onClear = { store.clearHistory() },
            onBack = { stage = Stage.DECKS },
        )

        Stage.FIELDS -> FieldScreen(
            deckName = deckName, sample = notes.first().second,
            qFields = qFields, aFields = aFields, mode = mode, settings = settings,
            error = fieldError,
            onToggleQ = { i -> if (qFields.contains(i)) qFields.remove(i) else qFields.add(i); fieldError = null },
            onToggleA = { i -> if (aFields.contains(i)) aFields.remove(i) else aFields.add(i); fieldError = null },
            onMode = { mode = it },
            onChangeSettings = { settings = it; store.saveSettings(it) },
            onStart = {
                if (qFields.isEmpty() || aFields.isEmpty()) {
                    fieldError = "問題側とこたえ側を、それぞれ1つ以上選んでください。"
                    return@FieldScreen
                }
                if (mode == Mode.QUIZ) {
                    val built = buildRoundItems()
                    if (built.isEmpty()) fieldError = "この組み合わせでは問題を作れませんでした。"
                    else {
                        config = RoundConfig(built, settings.gameMode, settings.reverse,
                            settings.timeLimitSec, settings.autoAdvance, settings.feedbackDeci)
                        round++; stage = Stage.QUIZ
                    }
                } else {
                    val built = buildFlash()
                    if (built.isEmpty()) fieldError = "この組み合わせでは表示できるカードがありません。"
                    else { flashItems = built; round++; stage = Stage.FLASHCARD }
                }
            },
            onBack = { stage = Stage.DECKS },
        )

        Stage.QUIZ -> key(round) {
            QuizScreen(
                config = config!!,
                onFinish = { logs, score -> recordAndFinish(logs, score) },
                onQuit = { stage = Stage.DECKS },
            )
        }

        Stage.FLASHCARD -> key(round) {
            FlashcardScreen(
                items = flashItems, secDeci = settings.flashcardDeci,
                onDone = { stage = Stage.FIELDS },
            )
        }

        Stage.RESULT -> {
            val missed = lastLogs.filter { !it.correct }.map { it.item }
            ResultScreen(
                logs = lastLogs, score = lastScore,
                countLabel = if (settings.count < 0) "全部" else "${settings.count}問",
                missedCount = missed.size,
                onRetry = {
                    config = RoundConfig(buildRoundItems(), settings.gameMode, settings.reverse,
                        settings.timeLimitSec, settings.autoAdvance, settings.feedbackDeci)
                    round++; stage = Stage.QUIZ
                },
                onRetryMissed = {
                    config = RoundConfig(missed, GameMode.NORMAL, settings.reverse,
                        settings.timeLimitSec, settings.autoAdvance, settings.feedbackDeci)
                    round++; stage = Stage.QUIZ
                },
                onDecks = { stage = Stage.DECKS },
            )
        }
    }
}

@Composable
private fun <T> ChipRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            options.forEach { (text, value) ->
                if (value == selected) Button(onClick = { onSelect(value) }) { Text(text) }
                else OutlinedButton(onClick = { onSelect(value) }) { Text(text) }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("漢字クイズ", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("AnkiDroidのデッキを読み込んでクイズを出題します。\n読み取りのみで、復習スケジュールには影響しません。",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("AnkiDroidへのアクセスを許可する") }
    }
}

@Composable
private fun DeckScreen(
    decks: List<DeckInfo>?, error: String?, streak: Int,
    onRetry: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit,
    onSelect: (DeckInfo) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("デッキを選ぶ", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (streak > 0) Text("🔥 $streak 日連続", fontSize = 13.sp, color = ComboOrange)
            }
            Row {
                TextButton(onClick = onHistory) { Text("履歴") }
                TextButton(onClick = onSettings) { Text("設定") }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            error != null -> {
                Text(error, color = WrongRed)
                Spacer(Modifier.height(16.dp)); Button(onClick = onRetry) { Text("再試行") }
            }
            decks == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            decks.isEmpty() -> Text("デッキが見つかりませんでした。")
            else -> LazyColumn {
                items(decks) { deck ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(deck) },
                        tonalElevation = 2.dp, shape = RoundedCornerShape(12.dp),
                    ) { Text(deck.name, modifier = Modifier.padding(16.dp), fontSize = 17.sp) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: Settings, onChange: (Settings) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("設定", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        ChipRow("出題数（通常モード・めくり）",
            listOf("10問" to 10, "20問" to 20, "50問" to 50, "全部" to -1),
            settings.count) { onChange(settings.copy(count = it)) }

        ChipRow("制限時間（1問あたり）",
            listOf("無制限" to 0, "10秒" to 10, "15秒" to 15, "20秒" to 20, "30秒" to 30),
            settings.timeLimitSec) { onChange(settings.copy(timeLimitSec = it)) }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("解答後に自動で次へ", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("オフにすると「次へ」を押すまで待ちます",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.autoAdvance,
                onCheckedChange = { onChange(settings.copy(autoAdvance = it)) })
        }
        if (settings.autoAdvance) {
            ChipRow("解答の表示時間",
                listOf("1.0秒" to 10, "1.5秒" to 15, "2.0秒" to 20, "3.0秒" to 30),
                settings.feedbackDeci) { onChange(settings.copy(feedbackDeci = it)) }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        ChipRow("めくりモード：1枚の表示時間",
            listOf("2秒" to 20, "3秒" to 30, "5秒" to 50, "8秒" to 80),
            settings.flashcardDeci) { onChange(settings.copy(flashcardDeci = it)) }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("もどる") }
    }
}

@Composable
private fun HistoryScreen(entries: List<HistoryEntry>, onClear: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    var cleared by remember { mutableStateOf(false) }
    val list = if (cleared) emptyList() else entries
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.JAPAN) }
    val rates = list.reversed().map { if (it.total == 0) 0 else it.correct * 100 / it.total }
        .takeLast(20)

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ゲーム履歴", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (list.isNotEmpty()) TextButton(onClick = { onClear(); cleared = true }) { Text("消去") }
        }
        Spacer(Modifier.height(8.dp))

        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("まだ記録がありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text("正答率の推移", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            AccuracyChart(rates)
            Spacer(Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(list) { e ->
                    val rate = if (e.total == 0) 0 else e.correct * 100 / e.total
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 2.dp, shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(e.deck, fontWeight = FontWeight.Bold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(fmt.format(Date(e.timeMillis)), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("SCORE ${e.score}　正解 ${e.correct}/${e.total}（$rate%）", fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("もどる") }
    }
}

@Composable
private fun AccuracyChart(rates: List<Int>) {
    if (rates.size < 2) {
        Text("2回以上プレイするとグラフが表示されます",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val line = MaterialTheme.colorScheme.primary
    val grid = Color(0xFF555555)
    Canvas(modifier = Modifier.fillMaxWidth().height(130.dp).padding(vertical = 8.dp)) {
        val w = size.width; val h = size.height
        val n = rates.size
        listOf(0, 50, 100).forEach { g ->
            val y = h - h * g / 100f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        fun pt(i: Int, r: Int) = Offset(if (n == 1) 0f else w * i / (n - 1), h - h * r / 100f)
        for (i in 0 until n - 1) {
            drawLine(line, pt(i, rates[i]), pt(i + 1, rates[i + 1]),
                strokeWidth = 4f, cap = StrokeCap.Round)
        }
        rates.forEachIndexed { i, r -> drawCircle(line, 6f, pt(i, r)) }
    }
}

@Composable
private fun FieldScreen(
    deckName: String, sample: List<String>,
    qFields: List<Int>, aFields: List<Int>, mode: Mode, settings: Settings,
    error: String?,
    onToggleQ: (Int) -> Unit, onToggleA: (Int) -> Unit,
    onMode: (Mode) -> Unit, onChangeSettings: (Settings) -> Unit,
    onStart: () -> Unit, onBack: () -> Unit,
) {
    BackHandler { onBack() }
    fun preview(i: Int, v: String): String {
        val t = cleanText(v).replace("\n", " ⏎ ")
        val body = if (t.length > 24) t.take(24) + "…" else t
        return "フィールド${i + 1}：" + body.ifBlank { "（空）" }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text(deckName, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))

        ChipRow("モード",
            listOf("クイズ" to Mode.QUIZ, "めくり（自動送り）" to Mode.FLASHCARD),
            mode, onMode)

        if (mode == Mode.QUIZ) {
            ChipRow("ゲーム形式",
                listOf("通常" to GameMode.NORMAL, "サバイバル(3ミス)" to GameMode.SURVIVAL,
                    "タイムアタック(60秒)" to GameMode.TIME_ATTACK),
                settings.gameMode) { onChangeSettings(settings.copy(gameMode = it)) }

            ChipRow("出題の向き",
                listOf("通常（漢字→よみ入力）" to false, "逆（よみ→漢字4択）" to true),
                settings.reverse) { onChangeSettings(settings.copy(reverse = it)) }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("苦手カードを優先", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("よく間違える語ほど出やすくなります",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.weakPriority,
                    onCheckedChange = { onChangeSettings(settings.copy(weakPriority = it)) })
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("問題として表示する側（複数選ぶと連結）", fontWeight = FontWeight.Bold)
        sample.forEachIndexed { i, v ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleQ(i) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = qFields.contains(i), onCheckedChange = { onToggleQ(i) })
                Text(preview(i, v), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(if (mode == Mode.QUIZ) "こたえ側（複数選ぶと連結）" else "裏に表示する側（複数選ぶと連結）",
            fontWeight = FontWeight.Bold)
        sample.forEachIndexed { i, v ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleA(i) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = aFields.contains(i), onCheckedChange = { onToggleA(i) })
                Text(preview(i, v), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp)); Text(error, color = WrongRed, fontSize = 13.sp)
        }

        Spacer(Modifier.height(24.dp))
        Row {
            OutlinedButton(onClick = onBack) { Text("もどる") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("スタート！") }
        }
    }
}

private enum class Phase { ASKING, FEEDBACK }

@Composable
private fun QuizScreen(
    config: RoundConfig,
    onFinish: (List<AnswerLog>, Int) -> Unit,
    onQuit: () -> Unit,
) {
    val items = config.items
    val reverse = config.reverse
    val isTA = config.gameMode == GameMode.TIME_ATTACK
    val isSurv = config.gameMode == GameMode.SURVIVAL
    val perQTimer = !isTA && config.timeLimitSec > 0
    val limit = config.timeLimitSec.toFloat()
    val pool = remember { items.map { it.question }.filter { it.isNotBlank() }.distinct() }

    var index by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var remaining by remember { mutableFloatStateOf(limit) }
    var globalRemaining by remember { mutableFloatStateOf(TIME_ATTACK_SEC) }
    var phase by remember { mutableStateOf(Phase.ASKING) }
    var lastCorrect by remember { mutableStateOf(false) }
    var lastGained by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var combo by remember { mutableIntStateOf(0) }
    var lives by remember { mutableIntStateOf(3) }
    var ended by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<AnswerLog>() }
    val focusRequester = remember { FocusRequester() }

    val item = items[index]
    val promptText = if (reverse) item.displayAnswer else item.question
    val answerText = if (reverse) item.question else item.displayAnswer
    val choices = remember(index) {
        if (!reverse) emptyList()
        else (pool.filter { it != item.question }.shuffled().take(3) + item.question).shuffled()
    }

    fun finish() { if (!ended) { ended = true; onFinish(logs.toList(), score) } }

    fun goNext() {
        if (phase != Phase.FEEDBACK) return
        if (isSurv && lives <= 0) { finish(); return }
        if (index + 1 >= items.size) { finish(); return }
        index++; input = ""; remaining = limit; phase = Phase.ASKING
    }

    fun judge(ok: Boolean, recorded: String) {
        if (phase != Phase.ASKING || ended) return
        if (ok) {
            combo++
            val speed = if (perQTimer) (remaining / limit * 50).roundToInt() else 0
            lastGained = 50 + speed + (combo - 1) * 5
            score += lastGained
        } else {
            combo = 0; lastGained = 0
            if (isSurv) lives -= 1
        }
        lastCorrect = ok
        logs.add(AnswerLog(item, ok, recorded))
        phase = Phase.FEEDBACK
    }

    BackHandler { showConfirm = true }

    LaunchedEffect(Unit) {
        if (isTA) {
            while (globalRemaining > 0f && !ended) {
                delay(100)
                if (!showConfirm) globalRemaining = (globalRemaining - 0.1f).coerceAtLeast(0f)
            }
            if (!ended) finish()
        }
    }

    LaunchedEffect(index, phase) {
        if (phase == Phase.ASKING) {
            delay(100)
            if (!reverse) runCatching { focusRequester.requestFocus() }
            if (perQTimer) {
                while (remaining > 0f && !ended) {
                    delay(100)
                    if (!showConfirm) remaining = (remaining - 0.1f).coerceAtLeast(0f)
                }
                if (!ended && remaining <= 0f) judge(false, "")
            }
        } else if (config.autoAdvance) {
            delay(config.feedbackDeci * 100L)
            goNext()
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("ホームに戻る") },
            text = { Text("プレイ中です。ホームに戻ると今回の結果は記録されません。") },
            confirmButton = { TextButton(onClick = { showConfirm = false; onQuit() }) { Text("戻る") } },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("続ける") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                isTA -> Text("残り ${globalRemaining.roundToInt()}秒",
                    fontWeight = FontWeight.Bold,
                    color = if (globalRemaining < 10f) WrongRed else MaterialTheme.colorScheme.onSurface)
                isSurv -> Text("❤".repeat(lives.coerceAtLeast(0)) + "🤍".repeat((3 - lives).coerceAtLeast(0)))
                else -> Text("${index + 1} / ${items.size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SCORE $score", fontWeight = FontWeight.Bold)
                TextButton(onClick = { showConfirm = true }) { Text("やめる") }
            }
        }
        Text(if (combo >= 2) "🔥 $combo COMBO" else "",
            color = ComboOrange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        if (perQTimer) {
            LinearProgressIndicator(
                progress = { remaining / limit },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (remaining < limit * 0.3f) WrongRed else MaterialTheme.colorScheme.primary,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (phase == Phase.ASKING) {
                Text(promptText, fontSize = 44.sp, lineHeight = 56.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            } else {
                val userInput = logs.lastOrNull()?.input ?: ""
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        when {
                            lastCorrect -> "⭕ 正解！"
                            userInput.isEmpty() -> "⏰ 時間切れ・パス"
                            else -> "❌ 不正解"
                        },
                        fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = if (lastCorrect) CorrectGreen else WrongRed)
                    Spacer(Modifier.height(14.dp))
                    Text(promptText, fontSize = 30.sp, lineHeight = 40.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(answerText, fontSize = 22.sp, lineHeight = 30.sp,
                        textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                        color = CorrectGreen)
                    if (!lastCorrect && userInput.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("あなたの解答：$userInput", fontSize = 14.sp, color = WrongRed)
                    }
                    if (lastCorrect) {
                        Spacer(Modifier.height(6.dp))
                        Text("+$lastGained", fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, color = CorrectGreen)
                    }
                }
            }
        }

        if (phase == Phase.ASKING) {
            if (reverse) {
                choices.forEach { opt ->
                    Button(onClick = { judge(opt == item.question, opt) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(opt.replace("\n", " "), fontSize = 20.sp)
                    }
                }
                TextButton(onClick = { judge(false, "") }, modifier = Modifier.align(Alignment.End)) {
                    Text("パス →")
                }
            } else {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    placeholder = { Text("よみを ひらがなで入力") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val n = normalizeKana(input)
                        if (n.isNotEmpty()) judge(n in item.accepted, input)
                    }),
                )
                TextButton(onClick = { judge(false, "") }, modifier = Modifier.align(Alignment.End)) {
                    Text("パス →")
                }
            }
        } else {
            val isLast = (isSurv && lives <= 0) || index + 1 >= items.size
            Button(onClick = { goNext() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (isLast) "結果を見る" else "次へ")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FlashcardScreen(items: List<Pair<String, String>>, secDeci: Int, onDone: () -> Unit) {
    BackHandler { onDone() }
    val perCardMillis = secDeci * 100L
    var index by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun toCard(i: Int) { index = i; elapsed = 0f }

    LaunchedEffect(index, paused, finished) {
        if (finished || paused) return@LaunchedEffect
        while (elapsed < perCardMillis) { delay(50); elapsed += 50 }
        if (index + 1 >= items.size) finished = true else toCard(index + 1)
    }

    if (finished) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("おわり 🎉", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("${items.size}枚めくりました", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { toCard(0); finished = false }, modifier = Modifier.fillMaxWidth()) {
                Text("もう一回")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("もどる") }
        }
        return
    }

    val (front, back) = items[index]
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${index + 1} / ${items.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onDone) { Text("やめる") }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (elapsed / perCardMillis).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().clickable { paused = !paused },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(front, fontSize = 40.sp, lineHeight = 52.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp))
                Spacer(Modifier.height(16.dp))
                Text(back, fontSize = 24.sp, lineHeight = 34.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (paused) {
                    Spacer(Modifier.height(16.dp))
                    Text("⏸ 一時停止中（タップで再開）", fontSize = 13.sp, color = ComboOrange)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { if (index > 0) toCard(index - 1) }, enabled = index > 0) {
                Text("← 前")
            }
            TextButton(onClick = { paused = !paused }) { Text(if (paused) "▶ 再開" else "⏸ 停止") }
            OutlinedButton(onClick = {
                if (index + 1 >= items.size) finished = true else toCard(index + 1)
            }) { Text("次 →") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ResultScreen(
    logs: List<AnswerLog>, score: Int, countLabel: String, missedCount: Int,
    onRetry: () -> Unit, onRetryMissed: () -> Unit, onDecks: () -> Unit,
) {
    BackHandler { onDecks() }
    val total = logs.size
    val correct = logs.count { it.correct }
    val rate = if (total == 0) 0 else correct * 100 / total
    val rank = when {
        rate == 100 -> "S"; rate >= 80 -> "A"; rate >= 60 -> "B"; rate >= 40 -> "C"; else -> "D"
    }
    val rankColor = when (rank) {
        "S" -> ComboOrange; "A" -> CorrectGreen
        "B" -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val missed = logs.filter { !it.correct }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Text(rank, fontSize = 88.sp, fontWeight = FontWeight.Bold, color = rankColor)
        Text("SCORE $score", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("正解 $correct / $total（$rate%）", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        if (missed.isEmpty()) {
            Text("全問正解！🎉", fontSize = 18.sp)
        } else {
            Text("復習しておきたい語", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))
            missed.forEach { log ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(log.item.question.replace("\n", " "), fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(log.item.displayAnswer.replace("\n", " "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        if (missedCount > 0) {
            Button(onClick = onRetryMissed, modifier = Modifier.fillMaxWidth()) {
                Text("間違いだけ復習（${missedCount}問）")
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("もう一回（べつの$countLabel）")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDecks, modifier = Modifier.fillMaxWidth()) { Text("デッキを選び直す") }
    }
}
