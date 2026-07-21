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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.input.KeyboardType
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
import kotlin.math.abs
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
private const val APP_VERSION = "1.4"
private const val THREE_CORRECT_TARGET = 3

// ============================================================
//  設定（端末に保存）
// ============================================================
enum class GameMode { NORMAL, SURVIVAL, TIME_ATTACK, THREE_CORRECT, MASTERY, WEAK_CHALLENGE, DAILY }
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
    val maxAttempts: Int = 3,
)

data class RoundConfig(
    val items: List<QuizItem>,
    val gameMode: GameMode,
    val reverse: Boolean,
    val timeLimitSec: Int,
    val autoAdvance: Boolean,
    val feedbackDeci: Int,
    val weakPriority: Boolean,
    val maxAttempts: Int,
    val choicePool: List<QuizItem>,
    val dailyKey: String? = null,
    val threeCorrectKey: String? = null,
    val initialThreeCorrectCounts: Map<Long, Int> = emptyMap(),
)

data class RoundResult(
    val logs: List<AnswerLog>,
    val score: Int,
    val maxCombo: Int,
    val durationSec: Int,
)

data class HistoryEntry(
    val timeMillis: Long,
    val deck: String,
    val score: Int,
    val correct: Int,
    val total: Int,
    val gameMode: GameMode = GameMode.NORMAL,
    val reverse: Boolean = false,
    val weakPriority: Boolean = false,
    val maxCombo: Int = 0,
    val durationSec: Int = 0,
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
        maxAttempts = sp.getInt("maxAttempts", 3).coerceIn(1, 99),
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
            .putInt("maxAttempts", s.maxAttempts.coerceIn(1, 99))
            .apply()
    }

    fun loadHistory(): List<HistoryEntry> {
        val raw = sp.getString("history", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val gameMode = runCatching {
                    GameMode.valueOf(o.optString("m", GameMode.NORMAL.name))
                }.getOrDefault(GameMode.NORMAL)
                HistoryEntry(
                    timeMillis = o.getLong("t"),
                    deck = o.getString("d"),
                    score = o.getInt("s"),
                    correct = o.getInt("c"),
                    total = o.getInt("n"),
                    gameMode = gameMode,
                    reverse = o.optBoolean("r", false),
                    weakPriority = o.optBoolean("w", false),
                    maxCombo = o.optInt("mc", 0),
                    durationSec = o.optInt("dur", 0),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addHistory(e: HistoryEntry) {
        val list = (listOf(e) + loadHistory()).take(50)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("t", it.timeMillis)
                put("d", it.deck)
                put("s", it.score)
                put("c", it.correct)
                put("n", it.total)
                put("m", it.gameMode.name)
                put("r", it.reverse)
                put("w", it.weakPriority)
                put("mc", it.maxCombo)
                put("dur", it.durationSec)
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

    fun loadThreeCorrectProgress(profileKey: String): MutableMap<Long, Int> {
        val raw = sp.getString("threeCorrectProgress", null) ?: return mutableMapOf()
        return runCatching {
            val root = JSONObject(raw)
            val profile = root.optJSONObject(profileKey) ?: return@runCatching mutableMapOf()
            val result = mutableMapOf<Long, Int>()
            profile.keys().forEach { noteId ->
                result[noteId.toLong()] = profile.optInt(noteId, 0).coerceIn(0, THREE_CORRECT_TARGET)
            }
            result
        }.getOrDefault(mutableMapOf())
    }

    fun saveThreeCorrectProgress(profileKey: String, progress: Map<Long, Int>) {
        val root = runCatching {
            JSONObject(sp.getString("threeCorrectProgress", "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val profile = JSONObject()
        progress.forEach { (noteId, count) ->
            val clamped = count.coerceIn(0, THREE_CORRECT_TARGET)
            if (clamped > 0) profile.put(noteId.toString(), clamped)
        }
        if (profile.length() == 0) root.remove(profileKey) else root.put(profileKey, profile)
        sp.edit().putString("threeCorrectProgress", root.toString()).apply()
    }

    fun updateThreeCorrectProgress(profileKey: String, noteId: Long, newCount: Int) {
        val progress = loadThreeCorrectProgress(profileKey)
        val clamped = newCount.coerceIn(0, THREE_CORRECT_TARGET)
        if (clamped == 0) progress.remove(noteId) else progress[noteId] = clamped
        saveThreeCorrectProgress(profileKey, progress)
    }

    fun clearThreeCorrectProgress(profileKey: String) {
        val root = runCatching {
            JSONObject(sp.getString("threeCorrectProgress", "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        root.remove(profileKey)
        sp.edit().putString("threeCorrectProgress", root.toString()).apply()
    }

    fun clearAllThreeCorrectProgress() = sp.edit().remove("threeCorrectProgress").apply()

    fun todayKey(): String = dayFmt.format(Date())

    fun isDailyCompleted(key: String): Boolean {
        val raw = sp.getString("dailyCompleted", null) ?: return false
        return runCatching { JSONObject(raw).optBoolean(key, false) }.getOrDefault(false)
    }

    fun markDailyCompleted(key: String) {
        val o = runCatching { JSONObject(sp.getString("dailyCompleted", "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        o.put(key, true)
        sp.edit().putString("dailyCompleted", o.toString()).apply()
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

private fun gameModeLabel(mode: GameMode): String = when (mode) {
    GameMode.NORMAL -> "通常"
    GameMode.SURVIVAL -> "サバイバル"
    GameMode.TIME_ATTACK -> "タイムアタック"
    GameMode.THREE_CORRECT -> "3回正解"
    GameMode.MASTERY -> "定着復習"
    GameMode.WEAK_CHALLENGE -> "苦手語チャレンジ"
    GameMode.DAILY -> "デイリーチャレンジ"
}

private fun compactLength(s: String): Int = cleanText(s).filterNot { it.isWhitespace() }.length

private fun kanjiCount(s: String): Int = s.count {
    it in '\u3400'..'\u4DBF' || it in '\u4E00'..'\u9FFF' || it in '\uF900'..'\uFAFF'
}

private fun smartReverseChoices(item: QuizItem, pool: List<QuizItem>): List<String> {
    val targetLength = compactLength(item.question)
    val targetKanji = kanjiCount(item.question)
    val targetAnswerLength = normalizeKana(item.displayAnswer).length
    val ranked = pool
        .filter {
            it.noteId != item.noteId && it.question.isNotBlank() && it.question != item.question
        }
        .distinctBy { it.question }
        .map { candidate ->
            val distance = abs(compactLength(candidate.question) - targetLength) * 4 +
                abs(kanjiCount(candidate.question) - targetKanji) * 6 +
                abs(normalizeKana(candidate.displayAnswer).length - targetAnswerLength)
            Triple(candidate.question, distance, Random.nextDouble())
        }
        .sortedWith(compareBy<Triple<String, Int, Double>> { it.second }.thenBy { it.third })
    val distractors = ranked.take(12).shuffled().take(3).map { it.first }
    return (distractors + item.question).distinct().shuffled()
}

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

private fun safeFileName(name: String): String =
    name.replace(Regex("[\\/:*?\"<>|]"), "_").take(80).ifBlank { "deck" }

private fun buildWebDeckJson(
    deckName: String,
    notes: List<Pair<Long, List<String>>>,
): String {
    val fieldCount = notes.maxOfOrNull { it.second.size } ?: 0
    val fields = JSONArray().apply {
        repeat(fieldCount) { index -> put("フィールド${index + 1}") }
    }
    val jsonNotes = JSONArray().apply {
        notes.forEach { (id, values) ->
            put(JSONObject().apply {
                put("id", id.toString())
                put("fields", JSONArray().apply {
                    repeat(fieldCount) { index -> put(values.getOrNull(index) ?: "") }
                })
            })
        }
    }
    return JSONObject().apply {
        put("format", "KanjiQuizWebDeck")
        put("version", 1)
        put("deck", JSONObject().apply {
            put("name", deckName)
            put("fields", fields)
            put("notes", jsonNotes)
        })
    }.toString(2)
}

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

    var pendingWebExport by remember { mutableStateOf<String?>(null) }
    var webExportMessage by remember { mutableStateOf<String?>(null) }
    val webExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val content = pendingWebExport ?: error("書き出すデータがありません。")
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("保存先を開けませんでした。")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            }.onSuccess {
                webExportMessage = "Web版用JSON（.txt）を保存しました。Firefox版でこのファイルを読み込んでください。"
            }.onFailure {
                webExportMessage = "保存に失敗しました: ${it.message}"
            }
        }
        pendingWebExport = null
    }

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
    var lastMaxCombo by remember { mutableIntStateOf(0) }
    var lastDurationSec by remember { mutableIntStateOf(0) }
    var lastConfig by remember { mutableStateOf<RoundConfig?>(null) }
    var lastHistoryRecorded by remember { mutableStateOf(true) }
    var lastMasteredCount by remember { mutableIntStateOf(0) }
    var lastThreeCorrectCompleted by remember { mutableIntStateOf(0) }
    var lastThreeCorrectTotal by remember { mutableIntStateOf(0) }

    fun weightedOrder(list: List<QuizItem>): List<QuizItem> {
        val stats = store.loadCardStats()
        return list.map { item ->
            val wrong = stats[item.noteId]?.getOrNull(1) ?: 0
            val weight = 1.0 + wrong
            val random = Random.nextDouble().coerceIn(1e-9, 1.0)
            item to random.pow(1.0 / weight)
        }.sortedByDescending { it.second }.map { it.first }
    }

    fun buildUsableItems(reverse: Boolean = settings.reverse): List<QuizItem> {
        return notes.map { (id, flds) ->
            QuizItem(
                noteId = id,
                question = joinFields(flds, qFields),
                displayAnswer = joinFields(flds, aFields),
                accepted = acceptedFrom(flds, aFields),
            )
        }.filter {
            if (reverse) it.question.isNotBlank() && it.displayAnswer.isNotBlank()
            else it.question.isNotBlank() && it.accepted.isNotEmpty()
        }
    }

    fun makeDailyKey(reverse: Boolean = settings.reverse): String = buildString {
        append(store.todayKey())
        append('|').append(deckName)
        append('|').append(qFields.sorted().joinToString(","))
        append('|').append(aFields.sorted().joinToString(","))
        append('|').append(reverse)
    }

    fun makeThreeCorrectKey(reverse: Boolean = settings.reverse): String = buildString {
        append("v1|").append(deckName)
        append('|').append(qFields.sorted().joinToString(","))
        append('|').append(aFields.sorted().joinToString(","))
        append('|').append(reverse)
    }

    fun selectRoundItems(usable: List<QuizItem>, gameMode: GameMode, reverse: Boolean): List<QuizItem> {
        return when (gameMode) {
            GameMode.NORMAL -> {
                val ordered = if (settings.weakPriority) weightedOrder(usable) else usable.shuffled()
                if (settings.count < 0) ordered else ordered.take(settings.count)
            }
            GameMode.SURVIVAL, GameMode.TIME_ATTACK -> {
                if (settings.weakPriority) weightedOrder(usable) else usable.shuffled()
            }
            GameMode.THREE_CORRECT -> {
                val ordered = if (settings.weakPriority) weightedOrder(usable) else usable.shuffled()
                if (settings.count < 0) ordered else ordered.take(settings.count)
            }
            GameMode.WEAK_CHALLENGE -> {
                val stats = store.loadCardStats()
                usable.filter { (stats[it.noteId]?.getOrNull(1) ?: 0) > 0 }
                    .map { item ->
                        val stat = stats[item.noteId] ?: intArrayOf(0, 0)
                        Triple(item, stat.getOrElse(1) { 0 }, stat.getOrElse(0) { 0 })
                    }
                    .sortedWith(
                        compareByDescending<Triple<QuizItem, Int, Int>> { it.second }
                            .thenBy { it.third }
                    )
                    .take(10)
                    .map { it.first }
            }
            GameMode.DAILY -> {
                val seed = makeDailyKey(reverse).hashCode()
                usable.sortedBy { it.noteId }.shuffled(Random(seed)).take(10)
            }
            GameMode.MASTERY -> usable
        }
    }

    fun createConfig(
        gameMode: GameMode,
        fixedItems: List<QuizItem>? = null,
        reverse: Boolean = settings.reverse,
    ): RoundConfig {
        val pool = buildUsableItems(reverse)
        val threeCorrectKey = if (gameMode == GameMode.THREE_CORRECT) makeThreeCorrectKey(reverse) else null
        val savedThreeCorrect = if (threeCorrectKey != null) {
            store.loadThreeCorrectProgress(threeCorrectKey)
        } else {
            emptyMap()
        }
        val eligiblePool = if (gameMode == GameMode.THREE_CORRECT) {
            pool.filter { (savedThreeCorrect[it.noteId] ?: 0) < THREE_CORRECT_TARGET }
        } else {
            pool
        }
        val selected = fixedItems ?: selectRoundItems(eligiblePool, gameMode, reverse)
        val distinctItems = selected.distinctBy { it.noteId }
        return RoundConfig(
            items = distinctItems,
            gameMode = gameMode,
            reverse = reverse,
            timeLimitSec = settings.timeLimitSec,
            autoAdvance = settings.autoAdvance,
            feedbackDeci = settings.feedbackDeci,
            weakPriority = settings.weakPriority,
            maxAttempts = settings.maxAttempts.coerceIn(1, 99),
            choicePool = pool,
            dailyKey = if (gameMode == GameMode.DAILY) makeDailyKey(reverse) else null,
            threeCorrectKey = threeCorrectKey,
            initialThreeCorrectCounts = if (gameMode == GameMode.THREE_CORRECT) {
                distinctItems.associate { it.noteId to (savedThreeCorrect[it.noteId] ?: 0) }
            } else {
                emptyMap()
            },
        )
    }

    fun buildFlash(): List<Pair<String, String>> {
        val list = notes.shuffled().mapNotNull { (_, flds) ->
            val question = joinFields(flds, qFields)
            val answer = joinFields(flds, aFields)
            if (question.isBlank() && answer.isBlank()) null else question to answer
        }
        return if (settings.count < 0) list else list.take(settings.count)
    }

    fun recordAndFinish(result: RoundResult, finishedConfig: RoundConfig) {
        val stats = store.loadCardStats()
        val beforeWrong = stats.mapValues { it.value.getOrElse(1) { 0 } }
        result.logs.forEach { log ->
            val current = stats[log.item.noteId] ?: intArrayOf(0, 0)
            current[0] += 1
            current[1] = if (log.correct) {
                maxOf(0, current[1] - 1)
            } else {
                minOf(10, current[1] + 2)
            }
            stats[log.item.noteId] = current
        }
        store.saveCardStats(stats)

        val completedSomething = result.logs.isNotEmpty()
        val dailyAlreadyCompleted = finishedConfig.gameMode == GameMode.DAILY &&
            finishedConfig.dailyKey?.let(store::isDailyCompleted) == true
        val shouldRecord = completedSomething && !dailyAlreadyCompleted

        if (completedSomething) store.bumpStreak()
        if (shouldRecord) {
            store.addHistory(
                HistoryEntry(
                    timeMillis = System.currentTimeMillis(),
                    deck = deckName,
                    score = result.score,
                    correct = result.logs.count { it.correct },
                    total = result.logs.size,
                    gameMode = finishedConfig.gameMode,
                    reverse = finishedConfig.reverse,
                    weakPriority = finishedConfig.weakPriority,
                    maxCombo = result.maxCombo,
                    durationSec = result.durationSec,
                )
            )
            if (finishedConfig.gameMode == GameMode.DAILY) {
                finishedConfig.dailyKey?.let(store::markDailyCompleted)
            }
        }

        val attemptedIds = result.logs.map { it.item.noteId }.toSet()
        lastMasteredCount = attemptedIds.count { id ->
            (beforeWrong[id] ?: 0) > 0 && (stats[id]?.getOrElse(1) { 0 } ?: 0) == 0
        }
        val finishedThreeCorrectKey = finishedConfig.threeCorrectKey
        if (finishedConfig.gameMode == GameMode.THREE_CORRECT && finishedThreeCorrectKey != null) {
            val allItems = buildUsableItems(finishedConfig.reverse)
            val progress = store.loadThreeCorrectProgress(finishedThreeCorrectKey)
            lastThreeCorrectTotal = allItems.size
            lastThreeCorrectCompleted = allItems.count {
                (progress[it.noteId] ?: 0) >= THREE_CORRECT_TARGET
            }
        } else {
            lastThreeCorrectTotal = 0
            lastThreeCorrectCompleted = 0
        }
        lastLogs = result.logs
        lastScore = result.score
        lastMaxCombo = result.maxCombo
        lastDurationSec = result.durationSec
        lastConfig = finishedConfig
        lastHistoryRecorded = shouldRecord
        stage = Stage.RESULT
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
                decks = decks,
                error = loadError,
                streak = store.currentStreak(),
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
                                qFields.clear()
                                qFields.add(0)
                                aFields.clear()
                                aFields.add(if (loaded.first().second.size > 1) 1 else 0)
                                mode = Mode.QUIZ
                                fieldError = null
                                stage = Stage.FIELDS
                            }
                        }.onFailure {
                            loadError = "ノートの取得に失敗しました: ${it.message}"
                        }
                    }
                },
            )
        }

        Stage.SETTINGS -> SettingsScreen(
            settings = settings,
            onChange = { settings = it; store.saveSettings(it) },
            onResetThreeCorrectAll = { store.clearAllThreeCorrectProgress() },
            onBack = { stage = Stage.DECKS },
        )

        Stage.HISTORY -> HistoryScreen(
            entries = store.loadHistory(),
            onClear = { store.clearHistory() },
            onBack = { stage = Stage.DECKS },
        )

        Stage.FIELDS -> {
            val dailyCompleted = if (settings.gameMode == GameMode.DAILY &&
                qFields.isNotEmpty() && aFields.isNotEmpty()
            ) {
                store.isDailyCompleted(makeDailyKey())
            } else {
                false
            }
            val threeCorrectSummary = if (settings.gameMode == GameMode.THREE_CORRECT &&
                qFields.isNotEmpty() && aFields.isNotEmpty()
            ) {
                val usable = buildUsableItems(settings.reverse)
                val progress = store.loadThreeCorrectProgress(makeThreeCorrectKey(settings.reverse))
                usable.count { (progress[it.noteId] ?: 0) >= THREE_CORRECT_TARGET } to usable.size
            } else {
                null
            }
            FieldScreen(
                deckName = deckName,
                sample = notes.first().second,
                qFields = qFields,
                aFields = aFields,
                mode = mode,
                settings = settings,
                dailyCompleted = dailyCompleted,
                threeCorrectSummary = threeCorrectSummary,
                error = fieldError,
                exportMessage = webExportMessage,
                onToggleQ = { i ->
                    if (qFields.contains(i)) qFields.remove(i) else qFields.add(i)
                    fieldError = null
                },
                onToggleA = { i ->
                    if (aFields.contains(i)) aFields.remove(i) else aFields.add(i)
                    fieldError = null
                },
                onMode = { mode = it },
                onChangeSettings = { settings = it; store.saveSettings(it) },
                onResetThreeCorrect = {
                    store.clearThreeCorrectProgress(makeThreeCorrectKey(settings.reverse))
                    fieldError = "累積回数をリセットしました。"
                },
                onExportWeb = {
                    pendingWebExport = buildWebDeckJson(deckName, notes)
                    webExportMessage = null
                    webExportLauncher.launch("${safeFileName(deckName)}.kanjiquiz.json.txt")
                },
                onStart = {
                    if (qFields.isEmpty() || aFields.isEmpty()) {
                        fieldError = "問題側とこたえ側を、それぞれ1つ以上選んでください。"
                        return@FieldScreen
                    }
                    if (mode == Mode.QUIZ) {
                        val builtConfig = createConfig(settings.gameMode)
                        if (builtConfig.items.isEmpty()) {
                            fieldError = when (settings.gameMode) {
                                GameMode.WEAK_CHALLENGE ->
                                    "苦手語がまだありません。まず通常モードなどで問題を解いてください。"
                                GameMode.THREE_CORRECT ->
                                    "この設定では、すべてのカードが累積3回正解に到達しています。"
                                else -> "この組み合わせでは問題を作れませんでした。"
                            }
                        } else {
                            config = builtConfig
                            round++
                            stage = Stage.QUIZ
                        }
                    } else {
                        val built = buildFlash()
                        if (built.isEmpty()) {
                            fieldError = "この組み合わせでは表示できるカードがありません。"
                        } else {
                            flashItems = built
                            round++
                            stage = Stage.FLASHCARD
                        }
                    }
                },
                onBack = { stage = Stage.DECKS },
            )
        }

        Stage.QUIZ -> key(round) {
            val currentConfig = config!!
            QuizScreen(
                config = currentConfig,
                onThreeCorrectProgressChanged = { noteId, count ->
                    currentConfig.threeCorrectKey?.let { key ->
                        store.updateThreeCorrectProgress(key, noteId, count)
                    }
                },
                onFinish = { result -> recordAndFinish(result, currentConfig) },
                onQuit = { stage = Stage.DECKS },
            )
        }

        Stage.FLASHCARD -> key(round) {
            FlashcardScreen(
                items = flashItems,
                secDeci = settings.flashcardDeci,
                onDone = { stage = Stage.FIELDS },
            )
        }

        Stage.RESULT -> {
            val finishedConfig = lastConfig!!
            val missed = lastLogs.filter { !it.correct }.map { it.item }.distinctBy { it.noteId }
            ResultScreen(
                logs = lastLogs,
                score = lastScore,
                gameMode = finishedConfig.gameMode,
                maxCombo = lastMaxCombo,
                durationSec = lastDurationSec,
                historyRecorded = lastHistoryRecorded,
                masteredCount = lastMasteredCount,
                missedCount = missed.size,
                threeCorrectCompleted = lastThreeCorrectCompleted,
                threeCorrectTotal = lastThreeCorrectTotal,
                onRetry = {
                    val nextConfig = if (finishedConfig.gameMode == GameMode.MASTERY) {
                        createConfig(
                            gameMode = GameMode.MASTERY,
                            fixedItems = finishedConfig.items,
                            reverse = finishedConfig.reverse,
                        )
                    } else {
                        createConfig(finishedConfig.gameMode, reverse = finishedConfig.reverse)
                    }
                    if (nextConfig.items.isEmpty()) {
                        fieldError = if (finishedConfig.gameMode == GameMode.THREE_CORRECT) {
                            "この設定では、すべてのカードが累積3回正解に到達しています。"
                        } else {
                            "この組み合わせでは問題を作れませんでした。"
                        }
                        stage = Stage.FIELDS
                    } else {
                        config = nextConfig
                        round++
                        stage = Stage.QUIZ
                    }
                },
                onRetryMissed = {
                    config = createConfig(
                        gameMode = GameMode.MASTERY,
                        fixedItems = missed,
                        reverse = finishedConfig.reverse,
                    )
                    round++
                    stage = Stage.QUIZ
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
        Text("漢字クイズ v$APP_VERSION", fontSize = 28.sp, fontWeight = FontWeight.Bold)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("デッキを選ぶ", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("v$APP_VERSION", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
private fun AttemptCountField(value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(2)
            text = digits
            digits.toIntOrNull()?.takeIf { it in 1..99 }?.let(onChange)
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        label = { Text("1問あたりの最大挑戦回数") },
        supportingText = {
            Text("不正解でも正解を表示せず、同じカードに再挑戦します（1〜99回）")
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
    )
}

@Composable
private fun SettingsScreen(
    settings: Settings,
    onChange: (Settings) -> Unit,
    onResetThreeCorrectAll: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    var showResetThreeCorrectConfirm by remember { mutableStateOf(false) }
    if (showResetThreeCorrectConfirm) {
        AlertDialog(
            onDismissRequest = { showResetThreeCorrectConfirm = false },
            title = { Text("3回正解の進捗をリセット") },
            text = { Text("すべてのデッキ・フィールド・出題方向の累積正解数を削除します。") },
            confirmButton = {
                TextButton(onClick = {
                    onResetThreeCorrectAll()
                    showResetThreeCorrectConfirm = false
                }) { Text("リセット") }
            },
            dismissButton = {
                TextButton(onClick = { showResetThreeCorrectConfirm = false }) { Text("キャンセル") }
            },
        )
    }
    Column(modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("設定", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        ChipRow("出題数（通常モード・めくり）",
            listOf("10問" to 10, "20問" to 20, "50問" to 50, "全部" to -1),
            settings.count) { onChange(settings.copy(count = it)) }

        ChipRow("制限時間（1問あたり）",
            listOf("無制限" to 0, "10秒" to 10, "15秒" to 15, "20秒" to 20, "30秒" to 30),
            settings.timeLimitSec) { onChange(settings.copy(timeLimitSec = it)) }

        AttemptCountField(settings.maxAttempts) { attempts ->
            onChange(settings.copy(maxAttempts = attempts))
        }

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

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text("3回正解モード", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "累積正解数はデッキ・フィールド・出題方向ごとに保存されます。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showResetThreeCorrectConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("すべての累積回数をリセット") }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("もどる") }
    }
}

@Composable
private fun HistoryScreen(entries: List<HistoryEntry>, onClear: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    var cleared by remember { mutableStateOf(false) }
    var deckFilter by remember { mutableStateOf("ALL") }
    var modeFilter by remember { mutableStateOf("ALL") }
    var directionFilter by remember { mutableStateOf("ALL") }
    val list = if (cleared) emptyList() else entries
    val deckOptions = list.map { it.deck }.distinct().sorted()
    val modeOptions = list.map { it.gameMode }.distinct().sortedBy { it.ordinal }
    val filtered = list.filter { entry ->
        (deckFilter == "ALL" || entry.deck == deckFilter) &&
            (modeFilter == "ALL" || entry.gameMode.name == modeFilter) &&
            (directionFilter == "ALL" ||
                (directionFilter == "REVERSE" && entry.reverse) ||
                (directionFilter == "FORWARD" && !entry.reverse))
    }
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.JAPAN) }
    val rates = filtered.reversed()
        .map { if (it.total == 0) 0 else it.correct * 100 / it.total }
        .takeLast(20)

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
        Spacer(Modifier.height(4.dp))

        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("まだ記録がありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ChipRow(
                label = "デッキ",
                options = listOf("全部" to "ALL") + deckOptions.map { it to it },
                selected = deckFilter,
                onSelect = { deckFilter = it },
            )
            ChipRow(
                label = "ゲーム形式",
                options = listOf("全部" to "ALL") + modeOptions.map { gameModeLabel(it) to it.name },
                selected = modeFilter,
                onSelect = { modeFilter = it },
            )
            ChipRow(
                label = "出題方向",
                options = listOf(
                    "全部" to "ALL",
                    "漢字→よみ" to "FORWARD",
                    "よみ→漢字" to "REVERSE",
                ),
                selected = directionFilter,
                onSelect = { directionFilter = it },
            )

            if (filtered.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("この条件の記録はありません",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("正答率の推移（選択中の条件）", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                AccuracyChart(rates)
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered) { entry ->
                        val rate = if (entry.total == 0) 0 else entry.correct * 100 / entry.total
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
                                    Text(
                                        entry.deck,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        fmt.format(Date(entry.timeMillis)),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                val direction = if (entry.reverse) "よみ→漢字" else "漢字→よみ"
                                val weak = if (entry.weakPriority &&
                                    entry.gameMode in listOf(
                                        GameMode.NORMAL,
                                        GameMode.SURVIVAL,
                                        GameMode.TIME_ATTACK,
                                        GameMode.THREE_CORRECT,
                                    )
                                ) "・苦手優先" else ""
                                Text(
                                    "${gameModeLabel(entry.gameMode)}・$direction$weak",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "SCORE ${entry.score}　正解 ${entry.correct}/${entry.total}（$rate%）",
                                    fontSize = 14.sp,
                                )
                                if (entry.maxCombo > 0 || entry.durationSec > 0) {
                                    Text(
                                        "最大コンボ ${entry.maxCombo}　${entry.durationSec}秒",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
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
    deckName: String,
    sample: List<String>,
    qFields: List<Int>,
    aFields: List<Int>,
    mode: Mode,
    settings: Settings,
    dailyCompleted: Boolean,
    threeCorrectSummary: Pair<Int, Int>?,
    error: String?,
    exportMessage: String?,
    onToggleQ: (Int) -> Unit,
    onToggleA: (Int) -> Unit,
    onMode: (Mode) -> Unit,
    onChangeSettings: (Settings) -> Unit,
    onResetThreeCorrect: () -> Unit,
    onExportWeb: () -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    var showResetCurrentConfirm by remember { mutableStateOf(false) }
    if (showResetCurrentConfirm) {
        AlertDialog(
            onDismissRequest = { showResetCurrentConfirm = false },
            title = { Text("この設定の進捗をリセット") },
            text = { Text("このデッキ・フィールド・出題方向の累積正解数を削除します。") },
            confirmButton = {
                TextButton(onClick = {
                    onResetThreeCorrect()
                    showResetCurrentConfirm = false
                }) { Text("リセット") }
            },
            dismissButton = {
                TextButton(onClick = { showResetCurrentConfirm = false }) { Text("キャンセル") }
            },
        )
    }
    fun preview(i: Int, value: String): String {
        val text = cleanText(value).replace("\n", " ⏎ ")
        val body = if (text.length > 24) text.take(24) + "…" else text
        return "フィールド${i + 1}：" + body.ifBlank { "（空）" }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text(
            deckName,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))

        ChipRow(
            "モード",
            listOf("クイズ" to Mode.QUIZ, "めくり（自動送り）" to Mode.FLASHCARD),
            mode,
            onMode,
        )

        if (mode == Mode.QUIZ) {
            ChipRow(
                "ゲーム形式",
                listOf(
                    "通常" to GameMode.NORMAL,
                    "サバイバル" to GameMode.SURVIVAL,
                    "タイムアタック" to GameMode.TIME_ATTACK,
                    "3回正解" to GameMode.THREE_CORRECT,
                    "苦手語" to GameMode.WEAK_CHALLENGE,
                    "デイリー" to GameMode.DAILY,
                ),
                settings.gameMode,
            ) { onChangeSettings(settings.copy(gameMode = it)) }

            Text(
                when (settings.gameMode) {
                    GameMode.NORMAL -> "設定した問題数を解きます。"
                    GameMode.SURVIVAL -> "3回間違えるまで、問題を繰り返し出題します。"
                    GameMode.TIME_ATTACK -> "60秒が終わるまで出題します。正解で時間が増えます。"
                    GameMode.THREE_CORRECT -> "過去のプレイを含め、正解で+1、不正解で-1。累積3回に到達したカードは以後出題しません。"
                    GameMode.WEAK_CHALLENGE -> "苦手度が高い語を最大10語出題します。"
                    GameMode.DAILY -> "今日固定の10問です。正式記録は1日1回です。"
                    GameMode.MASTERY -> "間違えた語を全て正解するまで繰り返します。"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.gameMode == GameMode.DAILY && dailyCompleted) {
                Text(
                    "今日の正式記録は保存済みです。再挑戦はできますが履歴には追加されません。",
                    fontSize = 12.sp,
                    color = ComboOrange,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (settings.gameMode == GameMode.THREE_CORRECT && threeCorrectSummary != null) {
                val (completed, total) = threeCorrectSummary
                Text(
                    "累積達成 $completed/$total",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (total > 0 && completed >= total) CorrectGreen
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = { showResetCurrentConfirm = true }) {
                    Text("この設定の累積回数をリセット")
                }
            }

            ChipRow(
                "出題の向き",
                listOf("通常（漢字→よみ入力）" to false, "逆（よみ→漢字4択）" to true),
                settings.reverse,
            ) { onChangeSettings(settings.copy(reverse = it)) }

            AttemptCountField(settings.maxAttempts) { attempts ->
                onChangeSettings(settings.copy(maxAttempts = attempts))
            }

            if (settings.gameMode in listOf(
                    GameMode.NORMAL,
                    GameMode.SURVIVAL,
                    GameMode.TIME_ATTACK,
                    GameMode.THREE_CORRECT,
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("苦手カードを優先", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "よく間違える語ほど先に出やすくなります",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.weakPriority,
                        onCheckedChange = { onChangeSettings(settings.copy(weakPriority = it)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("問題として表示する側（複数選ぶと連結）", fontWeight = FontWeight.Bold)
        sample.forEachIndexed { i, value ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleQ(i) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = qFields.contains(i), onCheckedChange = { onToggleQ(i) })
                Text(preview(i, value), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (mode == Mode.QUIZ) "こたえ側（複数選ぶと連結）"
            else "裏に表示する側（複数選ぶと連結）",
            fontWeight = FontWeight.Bold,
        )
        sample.forEachIndexed { i, value ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleA(i) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = aFields.contains(i), onCheckedChange = { onToggleA(i) })
                Text(preview(i, value), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onExportWeb,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Web版用JSONを保存")
        }
        Text(
            "Firefox版へデッキを移すための読み取り専用ファイルです。AnkiDroidのデータは変更しません。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (exportMessage != null) {
            Text(
                exportMessage,
                color = if (exportMessage.startsWith("保存に失敗")) WrongRed else CorrectGreen,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                error,
                color = if (error.startsWith("累積回数をリセット")) CorrectGreen else WrongRed,
                fontSize = 13.sp,
            )
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
    onThreeCorrectProgressChanged: (Long, Int) -> Unit,
    onFinish: (RoundResult) -> Unit,
    onQuit: () -> Unit,
) {
    val baseItems = config.items
    val reverse = config.reverse
    val isTimeAttack = config.gameMode == GameMode.TIME_ATTACK
    val isSurvival = config.gameMode == GameMode.SURVIVAL
    val isMastery = config.gameMode == GameMode.MASTERY
    val isThreeCorrect = config.gameMode == GameMode.THREE_CORRECT
    val isCycling = isTimeAttack || isSurvival
    val perQuestionTimer = !isTimeAttack && config.timeLimitSec > 0
    val limit = config.timeLimitSec.toFloat()
    val maxAttempts = config.maxAttempts.coerceIn(1, 99)

    var cycleItems by remember { mutableStateOf(baseItems) }
    val masteryQueue = remember {
        mutableStateListOf<QuizItem>().apply { addAll(baseItems.shuffled()) }
    }
    val threeCorrectQueue = remember {
        mutableStateListOf<QuizItem>().apply { addAll(baseItems.shuffled()) }
    }
    val threeCorrectCounts = remember {
        mutableStateMapOf<Long, Int>().apply {
            putAll(config.initialThreeCorrectCounts.mapValues { it.value.coerceIn(0, THREE_CORRECT_TARGET) })
        }
    }
    var index by remember { mutableIntStateOf(0) }
    var questionSerial by remember { mutableIntStateOf(0) }
    var attemptNumber by remember { mutableIntStateOf(1) }
    var retryNotice by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var remaining by remember { mutableFloatStateOf(limit) }
    var globalRemaining by remember { mutableFloatStateOf(TIME_ATTACK_SEC) }
    var phase by remember { mutableStateOf(Phase.ASKING) }
    var lastCorrect by remember { mutableStateOf(false) }
    var lastGained by remember { mutableIntStateOf(0) }
    var lastTimeBonus by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var combo by remember { mutableIntStateOf(0) }
    var maxCombo by remember { mutableIntStateOf(0) }
    var lives by remember { mutableIntStateOf(3) }
    var ended by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<AnswerLog>() }
    val focusRequester = remember { FocusRequester() }
    val startedAt = remember { System.currentTimeMillis() }

    val item = when {
        isMastery -> masteryQueue.first()
        isThreeCorrect -> threeCorrectQueue.first()
        else -> cycleItems[index]
    }
    val promptText = if (reverse) item.displayAnswer else item.question
    val answerText = if (reverse) item.question else item.displayAnswer
    val choices = remember(questionSerial, item.noteId) {
        if (reverse) smartReverseChoices(item, config.choicePool) else emptyList()
    }

    fun finish() {
        if (ended) return
        ended = true
        val durationSec = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
        onFinish(
            RoundResult(
                logs = logs.toList(),
                score = score,
                maxCombo = maxCombo,
                durationSec = durationSec,
            )
        )
    }

    fun resetForNextQuestion() {
        questionSerial++
        attemptNumber = 1
        retryNotice = null
        input = ""
        remaining = limit
        lastTimeBonus = 0
        phase = Phase.ASKING
    }

    fun retrySameQuestion() {
        attemptNumber++
        val remainingAttempts = maxAttempts - attemptNumber + 1
        retryNotice = "不正解。あと${remainingAttempts}回挑戦できます。"
        questionSerial++
        input = ""
        remaining = limit
        lastGained = 0
        lastTimeBonus = 0
        phase = Phase.ASKING
    }

    fun reshuffleAvoidingLast(last: QuizItem): List<QuizItem> {
        val next = baseItems.shuffled().toMutableList()
        if (next.size > 1 && next.first().noteId == last.noteId) {
            val swapIndex = next.indexOfFirst { it.noteId != last.noteId }
            if (swapIndex > 0) {
                val first = next[0]
                next[0] = next[swapIndex]
                next[swapIndex] = first
            }
        }
        return next
    }

    fun goNext() {
        if (phase != Phase.FEEDBACK || ended) return
        if (isSurvival && lives <= 0) {
            finish()
            return
        }

        if (isMastery) {
            if (lastCorrect && masteryQueue.size == 1) {
                finish()
                return
            }
            val answered = masteryQueue.removeAt(0)
            if (!lastCorrect) masteryQueue.add(answered)
            resetForNextQuestion()
            return
        }

        if (isThreeCorrect) {
            val currentCorrect = threeCorrectCounts[item.noteId] ?: 0
            if (currentCorrect >= THREE_CORRECT_TARGET && threeCorrectQueue.size == 1) {
                finish()
                return
            }
            val answered = threeCorrectQueue.removeAt(0)
            if (currentCorrect < THREE_CORRECT_TARGET) threeCorrectQueue.add(answered)
            resetForNextQuestion()
            return
        }

        if (index + 1 < cycleItems.size) {
            index++
            resetForNextQuestion()
            return
        }

        if (isCycling) {
            cycleItems = reshuffleAvoidingLast(item)
            index = 0
            resetForNextQuestion()
        } else {
            finish()
        }
    }

    fun judge(correct: Boolean, recorded: String) {
        if (phase != Phase.ASKING || ended) return

        val canRetry = !correct && recorded.isNotBlank() && attemptNumber < maxAttempts
        if (canRetry) {
            combo = 0
            retrySameQuestion()
            return
        }

        retryNotice = null
        if (correct) {
            combo++
            maxCombo = maxOf(maxCombo, combo)
            val speed = if (perQuestionTimer) {
                (remaining / limit * 50).roundToInt()
            } else {
                0
            }
            lastGained = 50 + speed + (combo - 1) * 5
            score += lastGained
            if (isThreeCorrect) {
                val current = threeCorrectCounts[item.noteId] ?: 0
                val updated = (current + 1).coerceAtMost(THREE_CORRECT_TARGET)
                threeCorrectCounts[item.noteId] = updated
                onThreeCorrectProgressChanged(item.noteId, updated)
            }
            if (isTimeAttack) {
                val comboTimeBonus = if (combo % 5 == 0) 2 else 0
                lastTimeBonus = 1 + comboTimeBonus
                globalRemaining = (globalRemaining + lastTimeBonus).coerceAtMost(TIME_ATTACK_SEC)
            }
        } else {
            combo = 0
            lastGained = 0
            lastTimeBonus = 0
            if (isSurvival) lives -= 1
            if (isThreeCorrect) {
                val current = threeCorrectCounts[item.noteId] ?: 0
                val updated = (current - 1).coerceAtLeast(0)
                threeCorrectCounts[item.noteId] = updated
                onThreeCorrectProgressChanged(item.noteId, updated)
            }
        }
        lastCorrect = correct
        logs.add(AnswerLog(item, correct, recorded))
        phase = Phase.FEEDBACK
    }

    BackHandler { showConfirm = true }

    LaunchedEffect(Unit) {
        if (isTimeAttack) {
            while (globalRemaining > 0f && !ended) {
                delay(100)
                if (!showConfirm) {
                    globalRemaining = (globalRemaining - 0.1f).coerceAtLeast(0f)
                }
            }
            if (!ended) finish()
        }
    }

    LaunchedEffect(questionSerial, phase) {
        if (phase == Phase.ASKING) {
            delay(100)
            if (!reverse) runCatching { focusRequester.requestFocus() }
            if (perQuestionTimer) {
                while (remaining > 0f && !ended && phase == Phase.ASKING) {
                    delay(100)
                    if (!showConfirm) {
                        remaining = (remaining - 0.1f).coerceAtLeast(0f)
                    }
                }
                if (!ended && remaining <= 0f && phase == Phase.ASKING) judge(false, "")
            }
        } else if (config.autoAdvance) {
            var waitDeci = config.feedbackDeci
            while (waitDeci > 0 && !ended && phase == Phase.FEEDBACK) {
                delay(100)
                if (!showConfirm) waitDeci--
            }
            if (!ended && phase == Phase.FEEDBACK) goNext()
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("ホームに戻る") },
            text = { Text("プレイ中です。ホームに戻ると今回の結果は記録されません。") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onQuit() }) { Text("戻る") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("続ける") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                isTimeAttack -> Text(
                    "残り ${globalRemaining.roundToInt()}秒・${logs.size}問",
                    fontWeight = FontWeight.Bold,
                    color = if (globalRemaining < 10f) WrongRed
                    else MaterialTheme.colorScheme.onSurface,
                )
                isSurvival -> Text(
                    "${"❤".repeat(lives.coerceAtLeast(0))}${"🤍".repeat((3 - lives).coerceIn(0, 3))}" +
                        "・${logs.size}問",
                )
                isMastery -> Text(
                    "残り ${masteryQueue.size}語",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                isThreeCorrect -> Text(
                    "残り ${threeCorrectQueue.size}語・累積 ${threeCorrectCounts.values.sum()}/${baseItems.size * THREE_CORRECT_TARGET}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "${index + 1} / ${cycleItems.size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SCORE $score", fontWeight = FontWeight.Bold)
                TextButton(onClick = { showConfirm = true }) { Text("やめる") }
            }
        }
        Text(
            if (combo >= 2) "🔥 $combo COMBO" else "",
            color = ComboOrange,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        if (phase == Phase.ASKING && maxAttempts > 1) {
            Text(
                "挑戦 $attemptNumber / $maxAttempts",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        retryNotice?.let { notice ->
            Text(
                notice,
                color = WrongRed,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        when {
            isTimeAttack -> LinearProgressIndicator(
                progress = { (globalRemaining / TIME_ATTACK_SEC).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (globalRemaining < 10f) WrongRed else MaterialTheme.colorScheme.primary,
            )
            perQuestionTimer -> LinearProgressIndicator(
                progress = { (remaining / limit).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (remaining < limit * 0.3f) WrongRed
                else MaterialTheme.colorScheme.primary,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (phase == Phase.ASKING) {
                SelectionContainer {
                    Text(
                        promptText,
                        fontSize = 44.sp,
                        lineHeight = 56.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                val userInput = logs.lastOrNull()?.input ?: ""
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        when {
                            lastCorrect -> "⭕ 正解！"
                            userInput.isEmpty() -> "⏰ 時間切れ・パス"
                            else -> "❌ 不正解"
                        },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (lastCorrect) CorrectGreen else WrongRed,
                    )
                    Spacer(Modifier.height(14.dp))
                    SelectionContainer {
                        Text(
                            promptText,
                            fontSize = 30.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            answerText,
                            fontSize = 22.sp,
                            lineHeight = 30.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            color = CorrectGreen,
                        )
                    }
                    if (!lastCorrect && userInput.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("あなたの解答：$userInput", fontSize = 14.sp, color = WrongRed)
                    }
                    if (lastCorrect && attemptNumber > 1) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${attemptNumber}回目の挑戦で正解",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isThreeCorrect) {
                        Spacer(Modifier.height(6.dp))
                        val currentCorrect = threeCorrectCounts[item.noteId] ?: 0
                        Text(
                            if (currentCorrect >= THREE_CORRECT_TARGET) {
                                "累積3回正解：以後は出題されません"
                            } else {
                                "このカードの正解回数 $currentCorrect/$THREE_CORRECT_TARGET"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (currentCorrect >= THREE_CORRECT_TARGET) CorrectGreen
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (lastCorrect) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "+$lastGained",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = CorrectGreen,
                        )
                        if (isTimeAttack && lastTimeBonus > 0) {
                            val bonusLabel = if (lastTimeBonus >= 3) {
                                "⏱ +${lastTimeBonus}秒（5コンボボーナス）"
                            } else {
                                "⏱ +${lastTimeBonus}秒"
                            }
                            Text(
                                bonusLabel,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = ComboOrange,
                            )
                        }
                    }
                }
            }
        }

        if (phase == Phase.ASKING) {
            if (reverse) {
                choices.forEach { option ->
                    Button(
                        onClick = { judge(option == item.question, option) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(option.replace("\n", " "), fontSize = 20.sp)
                    }
                }
                TextButton(
                    onClick = { judge(false, "") },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("パス →")
                }
            } else {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    placeholder = { Text("よみを ひらがなで入力") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val normalized = normalizeKana(input)
                        if (normalized.isNotEmpty()) judge(normalized in item.accepted, input)
                    }),
                )
                TextButton(
                    onClick = { judge(false, "") },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("パス →")
                }
            }
        } else {
            val finishesAfterFeedback = when {
                isSurvival && lives <= 0 -> true
                isMastery && lastCorrect && masteryQueue.size == 1 -> true
                isThreeCorrect && lastCorrect &&
                    (threeCorrectCounts[item.noteId] ?: 0) >= THREE_CORRECT_TARGET &&
                    threeCorrectQueue.size == 1 -> true
                !isCycling && !isMastery && !isThreeCorrect && index + 1 >= cycleItems.size -> true
                else -> false
            }
            Button(onClick = { goNext() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (finishesAfterFeedback) "結果を見る" else "次へ")
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
                SelectionContainer {
                    Text(front, fontSize = 40.sp, lineHeight = 52.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp))
                Spacer(Modifier.height(16.dp))
                SelectionContainer {
                    Text(back, fontSize = 24.sp, lineHeight = 34.sp, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
    logs: List<AnswerLog>,
    score: Int,
    gameMode: GameMode,
    maxCombo: Int,
    durationSec: Int,
    historyRecorded: Boolean,
    masteredCount: Int,
    missedCount: Int,
    threeCorrectCompleted: Int,
    threeCorrectTotal: Int,
    onRetry: () -> Unit,
    onRetryMissed: () -> Unit,
    onDecks: () -> Unit,
) {
    BackHandler { onDecks() }
    val total = logs.size
    val correct = logs.count { it.correct }
    val rate = if (total == 0) 0 else correct * 100 / total
    val rank = when {
        rate == 100 -> "S"
        rate >= 80 -> "A"
        rate >= 60 -> "B"
        rate >= 40 -> "C"
        else -> "D"
    }
    val rankColor = when (rank) {
        "S" -> ComboOrange
        "A" -> CorrectGreen
        "B" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val missed = logs.filter { !it.correct }.distinctBy { it.item.noteId }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Text(gameModeLabel(gameMode), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(rank, fontSize = 88.sp, fontWeight = FontWeight.Bold, color = rankColor)
        Text("SCORE $score", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "正解 $correct / $total（$rate%）",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "最大コンボ $maxCombo　プレイ時間 ${durationSec}秒",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!historyRecorded) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (gameMode == GameMode.DAILY && total > 0) {
                    "今日の正式記録はすでに保存済みのため、今回は履歴に追加していません。"
                } else {
                    "回答がなかったため、今回は履歴に追加していません。"
                },
                fontSize = 12.sp,
                color = ComboOrange,
                textAlign = TextAlign.Center,
            )
        }

        if (gameMode == GameMode.WEAK_CHALLENGE && masteredCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "苦手語を${masteredCount}語克服しました。",
                color = CorrectGreen,
                fontWeight = FontWeight.Bold,
            )
        }
        if (gameMode == GameMode.MASTERY) {
            Spacer(Modifier.height(8.dp))
            Text(
                "すべての語を正解するまで復習しました。",
                color = CorrectGreen,
                fontWeight = FontWeight.Bold,
            )
        }
        if (gameMode == GameMode.THREE_CORRECT) {
            Spacer(Modifier.height(8.dp))
            val allCompleted = threeCorrectTotal > 0 && threeCorrectCompleted >= threeCorrectTotal
            Text(
                if (allCompleted) {
                    "この設定の全カードが累積3回正解に到達しました。"
                } else {
                    "累積達成 $threeCorrectCompleted/$threeCorrectTotal"
                },
                color = if (allCompleted) CorrectGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(24.dp))
        if (missed.isEmpty()) {
            Text("全問正解！🎉", fontSize = 18.sp)
        } else {
            Text(
                if (gameMode == GameMode.MASTERY) "途中で間違えた語" else "復習しておきたい語",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(Modifier.height(8.dp))
            missed.forEach { log ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        log.item.question.replace("\n", " "),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        log.item.displayAnswer.replace("\n", " "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        if (missedCount > 0 && gameMode != GameMode.MASTERY) {
            Button(onClick = onRetryMissed, modifier = Modifier.fillMaxWidth()) {
                Text("定着するまで復習（${missedCount}語）")
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (gameMode) {
                    GameMode.DAILY -> "今日の問題をもう一回"
                    GameMode.MASTERY -> "同じ語をもう一回"
                    GameMode.WEAK_CHALLENGE -> "苦手語チャレンジをもう一回"
                    else -> "同じゲーム形式でもう一回"
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDecks, modifier = Modifier.fillMaxWidth()) {
            Text("デッキを選び直す")
        }
    }
}

