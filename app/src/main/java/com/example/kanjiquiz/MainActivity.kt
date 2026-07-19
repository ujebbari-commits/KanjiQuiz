package com.example.kanjiquiz

import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlin.math.roundToInt

// ============================================================
//  調整できる設定（ここを書き換えるだけで挙動が変わります）
// ============================================================
private const val QUESTIONS_PER_ROUND = 10   // 1ラウンドの問題数
private const val TIME_LIMIT_SEC = 15f       // 1問の制限時間（秒）
private const val FEEDBACK_MILLIS = 1500L    // 正解/不正解表示の自動送り時間

// ============================================================
//  AnkiDroid 公式API（ContentProvider）の接続先
// ============================================================
private const val ANKI_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
private val DECKS_URI: Uri = Uri.parse("content://com.ichi2.anki.flashcards/decks")
private val NOTES_URI: Uri = Uri.parse("content://com.ichi2.anki.flashcards/notes")

// ============================================================
//  データ型
// ============================================================
data class DeckInfo(val id: Long, val name: String)

data class QuizItem(
    val question: String,        // 表示する問題（例: 難読漢字）
    val displayAnswer: String,   // 表示用のこたえ（フィールドの中身そのまま）
    val accepted: List<String>,  // 判定用に正規化した読みのリスト
)

data class AnswerLog(val item: QuizItem, val correct: Boolean, val input: String)

// ============================================================
//  テキスト処理
// ============================================================

/** HTMLタグ・[sound:...]・主なエンティティを取り除く */
private fun stripHtml(s: String): String =
    s.replace(Regex("\\[sound:[^\\]]*\\]"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "　")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

/** カタカナ → ひらがな */
private fun kataToHira(s: String): String =
    buildString {
        for (ch in s) append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch)
    }

/** 判定用の正規化: ひらがなに揃え、かな・長音記号以外（ピッチ記号や矢印など）を捨てる */
private fun normalizeKana(s: String): String =
    kataToHira(s).filter { it in 'ぁ'..'ゖ' || it == 'ー' }

/** こたえフィールドを 、 ・ / 空白 などで区切り、正規化した読み候補にする */
private fun parseAnswers(raw: String): List<String> =
    stripHtml(raw)
        .split(Regex("[、,，;；・/／\\s　]+"))
        .map { normalizeKana(it) }
        .filter { it.isNotEmpty() }
        .distinct()

// ============================================================
//  AnkiDroid へのアクセス（読み取りのみ。復習スケジュールには一切触れません）
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

/** デッキ名で Anki のブラウザ検索（deck:"..."）を実行し、各ノートのフィールド一覧を返す */
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}

private enum class Stage { DECKS, FIELDS, QUIZ, RESULT }

private val CorrectGreen = Color(0xFF81C784)
private val WrongRed = Color(0xFFE57373)
private val ComboOrange = Color(0xFFFFB74D)

// ============================================================
//  画面遷移の親
// ============================================================

@Composable
private fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, ANKI_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    var stage by remember { mutableStateOf(Stage.DECKS) }
    var deckReload by remember { mutableIntStateOf(0) }
    var decks by remember { mutableStateOf<List<DeckInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var deckName by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var qField by remember { mutableIntStateOf(0) }
    var aField by remember { mutableIntStateOf(1) }
    var fieldError by remember { mutableStateOf<String?>(null) }

    var quizItems by remember { mutableStateOf<List<QuizItem>>(emptyList()) }
    var round by remember { mutableIntStateOf(0) }
    var lastLogs by remember { mutableStateOf<List<AnswerLog>>(emptyList()) }
    var lastScore by remember { mutableIntStateOf(0) }

    fun buildQuiz(): List<QuizItem> =
        notes.shuffled().mapNotNull { flds ->
            val q = stripHtml(flds.getOrNull(qField) ?: "")
            val rawA = flds.getOrNull(aField) ?: ""
            val accepted = parseAnswers(rawA)
            if (q.isBlank() || accepted.isEmpty()) null
            else QuizItem(q, stripHtml(rawA), accepted)
        }.take(QUESTIONS_PER_ROUND)

    if (!granted) {
        PermissionScreen(onRequest = { permissionLauncher.launch(ANKI_PERMISSION) })
        return
    }

    when (stage) {
        Stage.DECKS -> {
            LaunchedEffect(deckReload) {
                loadError = null
                val result = withContext(Dispatchers.IO) {
                    runCatching { loadDecks(context.contentResolver) }
                }
                result
                    .onSuccess { decks = it }
                    .onFailure {
                        loadError = "AnkiDroidに接続できませんでした。\n" +
                            "・AnkiDroidがインストール済みか\n" +
                            "・AnkiDroidを一度起動したか\n" +
                            "・AnkiDroidの設定 → 高度な設定 → 「AnkiDroid API」が有効か\n" +
                            "を確認して、もう一度お試しください。"
                    }
            }
            DeckScreen(
                decks = decks,
                error = loadError,
                onRetry = { decks = null; deckReload++ },
                onSelect = { deck ->
                    scope.launch {
                        loadError = null
                        val result = withContext(Dispatchers.IO) {
                            runCatching { loadNotes(context.contentResolver, deck.name) }
                        }
                        result
                            .onSuccess { loaded ->
                                if (loaded.isEmpty()) {
                                    loadError = "「${deck.name}」からノートを取得できませんでした。"
                                } else {
                                    deckName = deck.name
                                    notes = loaded
                                    qField = 0
                                    aField = if (loaded.first().size > 1) 1 else 0
                                    fieldError = null
                                    stage = Stage.FIELDS
                                }
                            }
                            .onFailure { loadError = "ノートの取得に失敗しました: ${it.message}" }
                    }
                },
            )
        }

        Stage.FIELDS -> {
            FieldScreen(
                deckName = deckName,
                sample = notes.first(),
                qField = qField,
                aField = aField,
                error = fieldError,
                onQ = { qField = it; fieldError = null },
                onA = { aField = it; fieldError = null },
                onStart = {
                    val built = buildQuiz()
                    if (built.isEmpty()) {
                        fieldError = "このフィールドの組み合わせでは問題を作れませんでした。" +
                            "読みが「かな」で入っているフィールドを「こたえ」に選んでください。"
                    } else {
                        quizItems = built
                        round++
                        stage = Stage.QUIZ
                    }
                },
                onBack = { stage = Stage.DECKS },
            )
        }

        Stage.QUIZ -> {
            // round をキーにして、ラウンドごとにクイズ画面の状態をリセットする
            androidx.compose.runtime.key(round) {
                QuizScreen(
                    items = quizItems,
                    onFinish = { logs, score ->
                        lastLogs = logs
                        lastScore = score
                        stage = Stage.RESULT
                    },
                )
            }
        }

        Stage.RESULT -> {
            ResultScreen(
                logs = lastLogs,
                score = lastScore,
                onRetry = {
                    quizItems = buildQuiz()
                    round++
                    stage = Stage.QUIZ
                },
                onDecks = { stage = Stage.DECKS },
            )
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
            "AnkiDroidのデッキを読み込んでクイズを出題します。\n" +
                "読み取りだけを行い、復習スケジュールには影響しません。",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("AnkiDroidへのアクセスを許可する") }
        Spacer(Modifier.height(12.dp))
        Text(
            "※ AnkiDroidがインストールされている必要があります",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    onSelect: (DeckInfo) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("デッキを選ぶ", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        when {
            error != null -> {
                Text(error, color = WrongRed)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("再試行") }
            }
            decks == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            decks.isEmpty() -> {
                Text("デッキが見つかりませんでした。AnkiDroid側にデッキがあるか確認してください。")
            }
            else -> {
                LazyColumn {
                    items(decks) { deck ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(deck) },
                            tonalElevation = 2.dp,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                deck.name,
                                modifier = Modifier.padding(16.dp),
                                fontSize = 17.sp,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

// ============================================================
//  フィールド選択画面（問題に使う面と、読みが入っている面を選ぶ）
// ============================================================

@Composable
private fun FieldScreen(
    deckName: String,
    sample: List<String>,
    qField: Int,
    aField: Int,
    error: String?,
    onQ: (Int) -> Unit,
    onA: (Int) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    fun preview(index: Int, value: String): String {
        val t = stripHtml(value)
        val body = if (t.length > 20) t.take(20) + "…" else t
        return "フィールド${index + 1}：" + body.ifBlank { "（空）" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(deckName, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "1枚目のカードを例に、使うフィールドを選んでください",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))

        Text("問題として表示する側", fontWeight = FontWeight.Bold)
        sample.forEachIndexed { i, v ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onQ(i) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = qField == i, onClick = { onQ(i) })
                Text(preview(i, v), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("こたえ（読み）が入っている側", fontWeight = FontWeight.Bold)
        sample.forEachIndexed { i, v ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onA(i) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = aField == i, onClick = { onA(i) })
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
            Button(
                onClick = onStart,
                enabled = qField != aField,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("スタート！") }
        }
    }
}

// ============================================================
//  クイズ画面（漢字でGO！風・入力式）
// ============================================================

private enum class Phase { ASKING, FEEDBACK }

@Composable
private fun QuizScreen(
    items: List<QuizItem>,
    onFinish: (List<AnswerLog>, Int) -> Unit,
) {
    var index by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var remaining by remember { mutableFloatStateOf(TIME_LIMIT_SEC) }
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
        if (index + 1 >= items.size) {
            onFinish(logs.toList(), score)
        } else {
            index++
            input = ""
            remaining = TIME_LIMIT_SEC
            phase = Phase.ASKING
        }
    }

    fun judge(answer: String) {
        if (phase != Phase.ASKING) return
        val ok = answer.isNotEmpty() && answer in item.accepted
        if (ok) {
            combo++
            lastGained = 50 + (remaining / TIME_LIMIT_SEC * 50).roundToInt() + (combo - 1) * 5
            score += lastGained
        } else {
            combo = 0
            lastGained = 0
        }
        lastCorrect = ok
        logs.add(AnswerLog(item, ok, answer))
        phase = Phase.FEEDBACK
    }

    LaunchedEffect(index, phase) {
        if (phase == Phase.ASKING) {
            delay(100)
            runCatching { focusRequester.requestFocus() }
            while (remaining > 0f) {
                delay(100)
                remaining = (remaining - 0.1f).coerceAtLeast(0f)
            }
            judge("") // 時間切れ
        } else {
            delay(FEEDBACK_MILLIS)
            goNext()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${index + 1} / ${items.size}",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("SCORE $score", fontWeight = FontWeight.Bold)
        }
        Text(
            if (combo >= 2) "🔥 ${combo} COMBO" else "",
            color = ComboOrange,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { remaining / TIME_LIMIT_SEC },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = if (remaining < TIME_LIMIT_SEC * 0.3f) WrongRed
                    else MaterialTheme.colorScheme.primary,
        )

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (phase == Phase.ASKING) {
                Text(
                    item.question,
                    fontSize = 46.sp,
                    lineHeight = 58.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            } else {
                val userInput = logs.lastOrNull()?.input ?: ""
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    Text(item.question, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(item.displayAnswer, fontSize = 20.sp,
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
                    val normalized = normalizeKana(input)
                    if (normalized.isNotEmpty()) judge(normalized)
                }),
            )
            TextButton(
                onClick = { judge("") },
                modifier = Modifier.align(Alignment.End),
            ) { Text("パス →") }
        } else {
            Button(onClick = { goNext() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (index + 1 >= items.size) "結果を見る" else "次へ")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ============================================================
//  結果画面
// ============================================================

@Composable
private fun ResultScreen(
    logs: List<AnswerLog>,
    score: Int,
    onRetry: () -> Unit,
    onDecks: () -> Unit,
) {
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
    val missed = logs.filter { !it.correct }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
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
                    Text(log.item.question, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text(log.item.displayAnswer,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("もう一回（べつの${QUESTIONS_PER_ROUND}問）")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDecks, modifier = Modifier.fillMaxWidth()) {
            Text("デッキを選び直す")
        }
    }
}
