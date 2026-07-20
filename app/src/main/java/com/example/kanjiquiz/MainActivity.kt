package com.example.kanjiquiz

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
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
import kotlin.math.roundToInt

// ============================================================
//  AnkiDroid 公式API（ContentProvider）— 読み取りのみ
// ============================================================
private const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
private val DECKS_URI: Uri = Uri.parse("content://com.ichi2.anki.flashcards/decks")
private val NOTES_URI: Uri = Uri.parse("content://com.ichi2.anki.flashcards/notes")

// ============================================================
//  設定（端末に保存される。初期値はここ）
// ============================================================
data class Settings(
    val count: Int = 10,          // 出題数。-1 = 全部
    val timeLimitSec: Int = 15,   // 制限時間（秒）。0 = 無制限
    val autoAdvance: Boolean = true, // 解答後に自動で次へ進むか
    val feedbackDeci: Int = 15,   // 解答表示の長さ（0.1秒単位。15 = 1.5秒）
    val flashcardDeci: Int = 30,  // めくりモード1枚の表示時間（0.1秒単位。30 = 3秒）
)

enum class Mode { QUIZ, FLASHCARD }

// ============================================================
//  保存領域（設定＋履歴）
// ============================================================
data class HistoryEntry(
    val timeMillis: Long,
    val deck: String,
    val score: Int,
    val correct: Int,
    val total: Int,
)

class Store(context: Context) {
    private val sp = context.getSharedPreferences("kanjiquiz", Context.MODE_PRIVATE)

    fun loadSettings(): Settings = Settings(
        count = sp.getInt("count", 10),
        timeLimitSec = sp.getInt("timeLimit", 15),
        autoAdvance = sp.getBoolean("autoAdvance", true),
        feedbackDeci = sp.getInt("feedbackDeci", 15),
        flashcardDeci = sp.getInt("flashcardDeci", 30),
    )

    fun saveSettings(s: Settings) {
        sp.edit()
            .putInt("count", s.count)
            .putInt("timeLimit", s.timeLimitSec)
            .putBoolean("autoAdvance", s.autoAdvance)
            .putInt("feedbackDeci", s.feedbackDeci)
            .putInt("flashcardDeci", s.flashcardDeci)
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
}

// ============================================================
//  テキスト処理
// ============================================================
data class DeckInfo(val id: Long, val name: String)

data class QuizItem(
    val question: String,        // 表示する問題（改行そのまま）
    val displayAnswer: String,   // 表示用のこたえ
    val accepted: List<String>,  // 判定用に正規化した読み
)

data class AnswerLog(val item: QuizItem, val correct: Boolean, val input: String)

/** HTMLを除去しつつ、<br> や </div></p> は改行として残す */
private fun cleanText(s: String): String =
    s.replace(Regex("\\[sound:[^\\]]*\\]"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(div|p|li|tr)>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("[ \\t]*\n[ \\t]*"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

private fun kataToHira(s: String): String =
    buildString { for (ch in s) append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch) }

private fun normalizeKana(s: String): String =
    kataToHira(s).filter { it in 'ぁ'..'ゖ' || it == 'ー' }

/** こたえフィールドを区切って正規化した読み候補にする（改行も区切り扱い） */
private fun parseAnswers(raw: String): List<String> =
    cleanText(raw)
        .split(Regex("[、,，;；・/／\\s　\\n]+"))
        .map { normalizeKana(it) }
        .filter { it.isNotEmpty() }
        .distinct()

/** 複数フィールドを選択順（昇順）に連結。空フィールドは飛ばす */
private fun joinFields(flds: List<String>, indices: List<Int>): String =
    indices.sorted()
        .mapNotNull { flds.getOrNull(it)?.let(::cleanText) }
        .filter { it.isNotBlank() }
        .joinToString("\n")

/** 複数のこたえフィールドから読み候補をまとめる */
private fun acceptedFrom(flds: List<String>, indices: List<Int>): List<String> =
    indices.flatMap { parseAnswers(flds.getOrNull(it) ?: "") }.distinct()

// ============================================================
//  AnkiDroid アクセス
// ============================================================
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

private fun loadNotes(resolver: ContentResolver, deckName: String): List<List<String>> {
    val safeName = deckName.replace("\"", "")
    val notes = mutableListOf<List<String>>()
    resolver.query(NOTES_URI, null, "deck:\"$safeName\"", null, null)?.use { c ->
        val fldsIdx = c.getColumnIndex("flds")
        if (fldsIdx < 0) return emptyList()
        while (c.moveToNext()) {
            val flds = c.getString(fldsIdx) ?: continue
            notes.add(flds.split('\u001f'))
        }
    }
    return notes
}

// ============================================================
//  Activity
// ============================================================
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

// ============================================================
//  親コンポーザブル
// ============================================================
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
    var notes by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    val qFields = remember { mutableStateListOf<Int>() }
    val aFields = remember { mutableStateListOf<Int>() }
    var mode by remember { mutableStateOf(Mode.QUIZ) }
    var fieldError by remember { mutableStateOf<String?>(null) }

    var quizItems by remember { mutableStateOf<List<QuizItem>>(emptyList()) }
    var flashItems by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var round by remember { mutableIntStateOf(0) }
    var lastLogs by remember { mutableStateOf<List<AnswerLog>>(emptyList()) }
    var lastScore by remember { mutableIntStateOf(0) }

    fun applyCount(list: List<QuizItem>) =
        if (settings.count < 0) list else list.take(settings.count)

    fun applyCountP(list: List<Pair<String, String>>) =
        if (settings.count < 0) list else list.take(settings.count)

    fun buildQuiz(): List<QuizItem> =
        applyCount(notes.shuffled().mapNotNull { flds ->
            val q = joinFields(flds, qFields)
            val accepted = acceptedFrom(flds, aFields)
            if (q.isBlank() || accepted.isEmpty()) null
            else QuizItem(q, joinFields(flds, aFields), accepted)
        })

    fun buildFlash(): List<Pair<String, String>> =
        applyCountP(notes.shuffled().mapNotNull { flds ->
            val q = joinFields(flds, qFields)
            val a = joinFields(flds, aFields)
            if (q.isBlank() && a.isBlank()) null else q to a
        })

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
                            "を確認して、もう一度お試しください。"
                    }
            }
            DeckScreen(
                decks = decks,
                error = loadError,
                onRetry = { decks = null; deckReload++ },
                onHistory = { stage = Stage.HISTORY },
                onSettings = { stage = Stage.SETTINGS },
                onSelect = { deck ->
                    scope.launch {
                        loadError = null
                        withContext(Dispatchers.IO) {
                            runCatching { loadNotes(context.contentResolver, deck.name) }
                        }.onSuccess { loaded ->
                            if (loaded.isEmpty()) {
                                loadError = "「${deck.name}」からノートを取得できませんでした。"
                            } else {
                                deckName = deck.name
                                notes = loaded
                                qFields.clear(); qFields.add(0)
                                aFields.clear(); aFields.add(if (loaded.first().size > 1) 1 else 0)
                                mode = Mode.QUIZ
                                fieldError = null
                                stage = Stage.FIELDS
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
            deckName = deckName,
            sample = notes.first(),
            qFields = qFields,
            aFields = aFields,
            mode = mode,
            error = fieldError,
            onToggleQ = { i -> if (qFields.contains(i)) qFields.remove(i) else qFields.add(i); fieldError = null },
            onToggleA = { i -> if (aFields.contains(i)) aFields.remove(i) else aFields.add(i); fieldError = null },
            onMode = { mode = it },
            onStart = {
                if (qFields.isEmpty() || aFields.isEmpty()) {
                    fieldError = "問題側とこたえ側を、それぞれ1つ以上選んでください。"
                    return@FieldScreen
                }
                if (mode == Mode.QUIZ) {
                    val built = buildQuiz()
                    if (built.isEmpty()) {
                        fieldError = "この組み合わせでは問題を作れませんでした。" +
                            "読みが「かな」で入っているフィールドをこたえ側に選んでください。"
                    } else { quizItems = built; round++; stage = Stage.QUIZ }
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
                items = quizItems,
                settings = settings,
                onFinish = { logs, score ->
                    lastLogs = logs; lastScore = score
                    val correct = logs.count { it.correct }
                    store.addHistory(
                        HistoryEntry(System.currentTimeMillis(), deckName, score, correct, logs.size)
                    )
                    stage = Stage.RESULT
                },
            )
        }

        Stage.FLASHCARD -> key(round) {
            FlashcardScreen(
                items = flashItems,
                secDeci = settings.flashcardDeci,
                onDone = { stage = Stage.FIELDS },
            )
        }

        Stage.RESULT -> ResultScreen(
            logs = lastLogs,
            score = lastScore,
            countLabel = if (settings.count < 0) "全部" else "${settings.count}問",
            onRetry = { quizItems = buildQuiz(); round++; stage = Stage.QUIZ },
            onDecks = { stage = Stage.DECKS },
        )
    }
}

// ============================================================
//  小さな共通部品：チップ選択列
// ============================================================
@Composable
private fun <T> ChipRow(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            options.forEach { (text, value) ->
                if (value == selected) {
                    Button(onClick = { onSelect(value) }) { Text(text) }
                } else {
                    OutlinedButton(onClick = { onSelect(value) }) { Text(text) }
                }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

// ============================================================
//  権限画面
// ============================================================
@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("漢字クイズ", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "AnkiDroidのデッキを読み込んでクイズを出題します。\n読み取りのみで、復習スケジュールには影響しません。",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("AnkiDroidへのアクセスを許可する") }
    }
}

// ============================================================
//  デッキ選択画面
// ============================================================
@Composable
private fun DeckScreen(
    decks: List<DeckInfo>?,
    error: String?,
    onRetry: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onSelect: (DeckInfo) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("デッキを選ぶ", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = onHistory) { Text("履歴") }
                TextButton(onClick = onSettings) { Text("設定") }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            error != null -> {
                Text(error, color = WrongRed)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("再試行") }
            }
            decks == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            decks.isEmpty() -> Text("デッキが見つかりませんでした。")
            else -> LazyColumn {
                items(decks) { deck ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(deck) },
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(deck.name, modifier = Modifier.padding(16.dp), fontSize = 17.sp) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ============================================================
//  設定画面
// ============================================================
@Composable
private fun SettingsScreen(
    settings: Settings,
    onChange: (Settings) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())
    ) {
        Text("設定", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        ChipRow(
            label = "出題数（クイズ・めくり共通）",
            options = listOf("10問" to 10, "20問" to 20, "50問" to 50, "全部" to -1),
            selected = settings.count,
            onSelect = { onChange(settings.copy(count = it)) },
        )

        ChipRow(
            label = "制限時間（クイズ）",
            options = listOf("無制限" to 0, "10秒" to 10, "15秒" to 15, "20秒" to 20, "30秒" to 30),
            selected = settings.timeLimitSec,
            onSelect = { onChange(settings.copy(timeLimitSec = it)) },
        )

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
            Switch(
                checked = settings.autoAdvance,
                onCheckedChange = { onChange(settings.copy(autoAdvance = it)) },
            )
        }

        if (settings.autoAdvance) {
            ChipRow(
                label = "解答の表示時間（自動で次へ）",
                options = listOf("1.0秒" to 10, "1.5秒" to 15, "2.0秒" to 20, "3.0秒" to 30),
                selected = settings.feedbackDeci,
                onSelect = { onChange(settings.copy(feedbackDeci = it)) },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        ChipRow(
            label = "めくりモード：1枚の表示時間",
            options = listOf("2秒" to 20, "3秒" to 30, "5秒" to 50, "8秒" to 80),
            selected = settings.flashcardDeci,
            onSelect = { onChange(settings.copy(flashcardDeci = it)) },
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("もどる") }
    }
}

// ============================================================
//  履歴画面
// ============================================================
@Composable
private fun HistoryScreen(
    entries: List<HistoryEntry>,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var cleared by remember { mutableStateOf(false) }
    val list = if (cleared) emptyList() else entries
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.JAPAN) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ゲーム履歴", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (list.isNotEmpty()) {
                TextButton(onClick = { onClear(); cleared = true }) { Text("消去") }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("まだ記録がありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(list) { e ->
                    val rate = if (e.total == 0) 0 else e.correct * 100 / e.total
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(e.deck, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                Text(fmt.format(Date(e.timeMillis)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("SCORE ${e.score}　正解 ${e.correct}/${e.total}（$rate%）",
                                fontSize = 14.sp)
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

// ============================================================
//  フィールド選択画面（複数選択・連結・モード選択）
// ============================================================
@Composable
private fun FieldScreen(
    deckName: String,
    sample: List<String>,
    qFields: List<Int>,
    aFields: List<Int>,
    mode: Mode,
    error: String?,
    onToggleQ: (Int) -> Unit,
    onToggleA: (Int) -> Unit,
    onMode: (Mode) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    fun preview(index: Int, value: String): String {
        val t = cleanText(value).replace("\n", " ⏎ ")
        val body = if (t.length > 24) t.take(24) + "…" else t
        return "フィールド${index + 1}：" + body.ifBlank { "（空）" }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())
    ) {
        Text(deckName, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))

        ChipRow(
            label = "モード",
            options = listOf("クイズ（入力）" to Mode.QUIZ, "めくり（自動送り）" to Mode.FLASHCARD),
            selected = mode,
            onSelect = onMode,
        )

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
        Text(
            if (mode == Mode.QUIZ) "こたえ（読み）が入っている側（複数選ぶと連結）"
            else "裏に表示する側（複数選ぶと連結）",
            fontWeight = FontWeight.Bold,
        )
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
            Spacer(Modifier.height(12.dp))
            Text(error, color = WrongRed, fontSize = 13.sp)
        }

        Spacer(Modifier.height(24.dp))
        Row {
            OutlinedButton(onClick = onBack) { Text("もどる") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("スタート！") }
        }
    }
}

// ============================================================
//  クイズ画面（入力式）
// ============================================================
private enum class Phase { ASKING, FEEDBACK }

@Composable
private fun QuizScreen(
    items: List<QuizItem>,
    settings: Settings,
    onFinish: (List<AnswerLog>, Int) -> Unit,
) {
    val limit = settings.timeLimitSec.toFloat()
    val unlimited = settings.timeLimitSec <= 0

    var index by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var remaining by remember { mutableFloatStateOf(limit) }
    var phase by remember { mutableStateOf(Phase.ASKING) }
    var lastCorrect by remember { mutableStateOf(false) }
    var lastGained by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var combo by remember { mutableIntStateOf(0) }
    val logs = remember { mutableStateListOf<AnswerLog>() }
    val focusRequester = remember { FocusRequester() }

    val item = items[index]

    fun goNext() {
        if (phase != Phase.FEEDBACK) return
        if (index + 1 >= items.size) onFinish(logs.toList(), score)
        else { index++; input = ""; remaining = limit; phase = Phase.ASKING }
    }

    fun judge(answer: String) {
        if (phase != Phase.ASKING) return
        val ok = answer.isNotEmpty() && answer in item.accepted
        if (ok) {
            combo++
            val speed = if (unlimited) 0 else (remaining / limit * 50).roundToInt()
            lastGained = 50 + speed + (combo - 1) * 5
            score += lastGained
        } else { combo = 0; lastGained = 0 }
        lastCorrect = ok
        logs.add(AnswerLog(item, ok, answer))
        phase = Phase.FEEDBACK
    }

    LaunchedEffect(index, phase) {
        if (phase == Phase.ASKING) {
            delay(100)
            runCatching { focusRequester.requestFocus() }
            if (!unlimited) {
                while (remaining > 0f) {
                    delay(100)
                    remaining = (remaining - 0.1f).coerceAtLeast(0f)
                }
                judge("")
            }
        } else if (settings.autoAdvance) {
            delay(settings.feedbackDeci * 100L)
            goNext()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${index + 1} / ${items.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("SCORE $score", fontWeight = FontWeight.Bold)
        }
        Text(if (combo >= 2) "🔥 $combo COMBO" else "",
            color = ComboOrange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        if (!unlimited) {
            LinearProgressIndicator(
                progress = { remaining / limit },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (remaining < limit * 0.3f) WrongRed else MaterialTheme.colorScheme.primary,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (phase == Phase.ASKING) {
                Text(item.question, fontSize = 44.sp, lineHeight = 56.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            } else {
                val userInput = logs.lastOrNull()?.input ?: ""
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when {
                            lastCorrect -> "⭕ 正解！"
                            userInput.isEmpty() -> "⏰ 時間切れ・パス"
                            else -> "❌ 不正解"
                        },
                        fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = if (lastCorrect) CorrectGreen else WrongRed,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(item.question, fontSize = 32.sp, lineHeight = 42.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(item.displayAnswer, fontSize = 20.sp, lineHeight = 28.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                placeholder = { Text("よみを ひらがなで入力") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val n = normalizeKana(input)
                    if (n.isNotEmpty()) judge(n)
                }),
            )
            TextButton(onClick = { judge("") }, modifier = Modifier.align(Alignment.End)) {
                Text("パス →")
            }
        } else {
            Button(onClick = { goNext() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (index + 1 >= items.size) "結果を見る" else "次へ")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ============================================================
//  めくりモード（表裏同時表示・自動送り・PPT風）
// ============================================================
@Composable
private fun FlashcardScreen(
    items: List<Pair<String, String>>,
    secDeci: Int,
    onDone: () -> Unit,
) {
    val perCardMillis = secDeci * 100L
    var index by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun toCard(i: Int) { index = i; elapsed = 0f }

    LaunchedEffect(index, paused, finished) {
        if (finished || paused) return@LaunchedEffect
        while (elapsed < perCardMillis) {
            delay(50)
            elapsed += 50
        }
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
            Text("${items.size}枚めくりました",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { toCard(0); finished = false },
                modifier = Modifier.fillMaxWidth()) { Text("もう一回") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("フィールド選択にもどる")
            }
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
            Text("${index + 1} / ${items.size}",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onDone) { Text("やめる") }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (elapsed / perCardMillis).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )

        // タップで一時停止／再開
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().clickable { paused = !paused },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(front, fontSize = 40.sp, lineHeight = 52.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp))
                Spacer(Modifier.height(16.dp))
                Text(back, fontSize = 24.sp, lineHeight = 34.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (paused) {
                    Spacer(Modifier.height(16.dp))
                    Text("⏸ 一時停止中（タップで再開）",
                        fontSize = 13.sp, color = ComboOrange)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { if (index > 0) toCard(index - 1) },
                enabled = index > 0,
            ) { Text("← 前") }
            TextButton(onClick = { paused = !paused }) {
                Text(if (paused) "▶ 再開" else "⏸ 停止")
            }
            OutlinedButton(onClick = {
                if (index + 1 >= items.size) finished = true else toCard(index + 1)
            }) { Text("次 →") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================================================
//  結果画面
// ============================================================
@Composable
private fun ResultScreen(
    logs: List<AnswerLog>,
    score: Int,
    countLabel: String,
    onRetry: () -> Unit,
    onDecks: () -> Unit,
) {
    val total = logs.size
    val correct = logs.count { it.correct }
    val rate = if (total == 0) 0 else correct * 100 / total
    val rank = when {
        rate == 100 -> "S"; rate >= 80 -> "A"; rate >= 60 -> "B"; rate >= 40 -> "C"; else -> "D"
    }
    val rankColor = when (rank) {
        "S" -> ComboOrange; "A" -> CorrectGreen
        "B" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val missed = logs.filter { !it.correct }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Text(rank, fontSize = 88.sp, fontWeight = FontWeight.Bold, color = rankColor)
        Text("SCORE $score", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("正解 $correct / $total（$rate%）",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        if (missed.isEmpty()) {
            Text("全問正解！🎉", fontSize = 18.sp)
        } else {
            Text("復習しておきたい語", fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(8.dp))
            missed.forEach { log ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(log.item.question.replace("\n", " "),
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(log.item.displayAnswer.replace("\n", " "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("もう一回（べつの$countLabel）")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDecks, modifier = Modifier.fillMaxWidth()) {
            Text("デッキを選び直す")
        }
    }
}
