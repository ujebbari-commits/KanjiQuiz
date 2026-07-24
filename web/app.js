"use strict";

const APP_VERSION = "0.1.24";
const DB_NAME = "KanjiQuizWeb";
const DB_VERSION = 1;
const STORE_DECKS = "decks";
const TIME_ATTACK_SEC = 60;
const THREE_CORRECT_TARGET = 3;
const HISTORY_LIMIT = 50;

const GAME_MODES = {
  NORMAL: "通常",
  SURVIVAL: "サバイバル",
  TIME_ATTACK: "タイムアタック",
  THREE_CORRECT: "3回正解",
  MASTERY: "定着復習",
  WEAK_CHALLENGE: "苦手語チャレンジ",
  DAILY: "デイリーチャレンジ"
};

const DEFAULT_SETTINGS = {
  count: 10,
  timeLimitSec: 15,
  autoAdvance: true,
  feedbackDeci: 15,
  flashcardDeci: 30,
  flashSpeechEnabled: false,
  flashSpeechTarget: "BOTH",
  flashSpeechRate: 1,
  flashFrontWaitDeci: 30,
  flashBackWaitDeci: 40,
  flashRepeatCount: 1,
  flashSpeechReadParentheses: false,
  flashShowBothInitially: false,
  flashManualAdvanceWhenShowBoth: true,
  edgeReaderCount: 20,
  reverse: false,
  gameMode: "NORMAL",
  weakPriority: true,
  maxAttempts: 3,
  gameFontSize: 44,
  pauseWhileSelecting: true,
  sharedThreeCorrectAllModes: false,
  newOnly: false
};

const app = document.getElementById("app");
const state = {
  screen: "home",
  decks: [],
  currentDeck: null,
  fieldConfig: null,
  importRaw: "",
  importFilename: "",
  importDraft: null,
  importError: "",
  importMode: "new",
  importTargetDeckId: "",
  quiz: null,
  flash: null,
  lastResult: null
};

let dbPromise;

function openDb() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_DECKS)) {
        db.createObjectStore(STORE_DECKS, { keyPath: "id" });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return dbPromise;
}

async function dbGetAllDecks() {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_DECKS, "readonly");
    const request = tx.objectStore(STORE_DECKS).getAll();
    request.onsuccess = () => resolve(request.result || []);
    request.onerror = () => reject(request.error);
  });
}

async function dbPutDeck(deck) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_DECKS, "readwrite");
    tx.objectStore(STORE_DECKS).put(deck);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

async function dbDeleteDeck(id) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_DECKS, "readwrite");
    tx.objectStore(STORE_DECKS).delete(id);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

function readJson(key, fallback) {
  try {
    const value = localStorage.getItem(key);
    return value == null ? fallback : JSON.parse(value);
  } catch {
    return fallback;
  }
}

function writeJson(key, value) {
  localStorage.setItem(key, JSON.stringify(value));
}

function loadSettings() {
  const saved = readJson("kq.settings", {});
  return {
    ...DEFAULT_SETTINGS,
    ...saved,
    maxAttempts: clampInt(saved.maxAttempts ?? DEFAULT_SETTINGS.maxAttempts, 1, 99),
    feedbackDeci: clampInt(saved.feedbackDeci ?? DEFAULT_SETTINGS.feedbackDeci, 1, 600),
    flashcardDeci: clampInt(saved.flashcardDeci ?? DEFAULT_SETTINGS.flashcardDeci, 3, 600),
    flashSpeechEnabled: Boolean(saved.flashSpeechEnabled),
    flashSpeechTarget: ["BOTH", "FRONT", "BACK"].includes(saved.flashSpeechTarget)
      ? saved.flashSpeechTarget
      : DEFAULT_SETTINGS.flashSpeechTarget,
    flashSpeechRate: Math.min(2, Math.max(0.5, Number(saved.flashSpeechRate ?? DEFAULT_SETTINGS.flashSpeechRate) || 1)),
    flashFrontWaitDeci: clampInt(saved.flashFrontWaitDeci ?? DEFAULT_SETTINGS.flashFrontWaitDeci, 0, 600),
    flashBackWaitDeci: clampInt(saved.flashBackWaitDeci ?? DEFAULT_SETTINGS.flashBackWaitDeci, 0, 600),
    flashRepeatCount: clampInt(saved.flashRepeatCount ?? DEFAULT_SETTINGS.flashRepeatCount, 1, 9),
    flashSpeechReadParentheses: Boolean(saved.flashSpeechReadParentheses),
    flashShowBothInitially: Boolean(saved.flashShowBothInitially),
    flashManualAdvanceWhenShowBoth: saved.flashManualAdvanceWhenShowBoth == null
      ? DEFAULT_SETTINGS.flashManualAdvanceWhenShowBoth
      : Boolean(saved.flashManualAdvanceWhenShowBoth),
    edgeReaderCount: clampInt(saved.edgeReaderCount ?? DEFAULT_SETTINGS.edgeReaderCount, 1, 9999),
    gameFontSize: clampInt(saved.gameFontSize ?? DEFAULT_SETTINGS.gameFontSize, 16, 96),
    gameMode: saved.gameMode === "DAILY" ? "NORMAL" : (saved.gameMode || DEFAULT_SETTINGS.gameMode),
    newOnly: Boolean(saved.newOnly)
  };
}

function saveSettings(settings) {
  writeJson("kq.settings", settings);
}

function gameFontStyle(value) {
  const size = clampInt(value, 16, 96);
  return [
    `--game-font-size:${size}px`,
    `--game-feedback-question-size:${Math.max(16, Math.round(size * 0.70))}px`,
    `--game-answer-size:${Math.max(16, Math.round(size * 0.55))}px`,
    `--game-choice-size:${Math.max(16, Math.min(36, Math.round(size * 0.48)))}px`,
    `--game-flash-answer-size:${Math.max(16, Math.round(size * 0.60))}px`
  ].join(";");
}

function applyGameFontStyle(element, value) {
  if (element) element.style.cssText += `;${gameFontStyle(value)}`;
}

let quizViewportBaseline = 0;
let quizInputFocused = false;
let suppressedQuizClick = null;

function currentQuizViewportHeight() {
  return Math.round(window.visualViewport?.height || window.innerHeight);
}

function setQuizKeyboardLayout(active) {
  const shell = document.querySelector(".quiz-shell");
  document.body.classList.toggle("quiz-keyboard-active", Boolean(active));
  if (!shell) return;
  shell.classList.toggle("keyboard-active", Boolean(active));
  if (active) {
    shell.style.setProperty("--quiz-viewport-height", `${currentQuizViewportHeight()}px`);
    requestAnimationFrame(() => window.scrollTo(0, 0));
  } else {
    shell.style.removeProperty("--quiz-viewport-height");
  }
}

function refreshQuizKeyboardLayout() {
  const input = document.getElementById("answer-input");
  const focused = Boolean(input && document.activeElement === input && quizInputFocused);
  const height = currentQuizViewportHeight();
  if (!focused) {
    quizViewportBaseline = Math.max(quizViewportBaseline, height);
    setQuizKeyboardLayout(false);
    return;
  }
  if (!quizViewportBaseline) quizViewportBaseline = height;
  const keyboardOpen = quizViewportBaseline - height >= 100;
  setQuizKeyboardLayout(keyboardOpen);
}

function loadHistory() {
  return readJson("kq.history", []);
}

function addHistory(entry) {
  writeJson("kq.history", [entry, ...loadHistory()].slice(0, HISTORY_LIMIT));
}

function loadCardStats() {
  return readJson("kq.cardStats", {});
}

function saveCardStats(stats) {
  writeJson("kq.cardStats", stats);
}

function loadCardEdits() {
  const value = readJson("kq.cardEdits", {});
  return value && typeof value === "object" ? value : {};
}

function cardEditEntry(deckId, noteId) {
  const root = loadCardEdits();
  const deckEdits = root[String(deckId)];
  const entry = deckEdits && typeof deckEdits === "object" ? deckEdits[String(noteId)] : null;
  return entry && typeof entry === "object" ? entry : null;
}

function effectiveNoteFields(deck, note) {
  const base = Array.from({ length: deck.fields.length }, (_, index) => String(note.fields[index] ?? ""));
  const entry = cardEditEntry(deck.id, note.id);
  if (!entry) return base;
  const byName = entry.byName && typeof entry.byName === "object" ? entry.byName : {};
  const byIndex = entry.byIndex && typeof entry.byIndex === "object" ? entry.byIndex : {};
  return base.map((value, index) => {
    const fieldName = String(deck.fields[index] ?? "");
    if (Object.prototype.hasOwnProperty.call(byName, fieldName)) return String(byName[fieldName] ?? "");
    if (Object.prototype.hasOwnProperty.call(byIndex, String(index))) return String(byIndex[String(index)] ?? "");
    return value;
  });
}

function saveCardEdit(deck, note, editedFields) {
  const root = loadCardEdits();
  const deckId = String(deck.id);
  const noteId = String(note.id);
  const byName = {};
  const byIndex = {};
  const names = deck.fields.map(String);
  const duplicateNames = new Set(names.filter((name, index) => names.indexOf(name) !== index));
  for (let index = 0; index < deck.fields.length; index += 1) {
    const base = String(note.fields[index] ?? "");
    const edited = String(editedFields[index] ?? "");
    if (edited === base) continue;
    const fieldName = names[index];
    if (fieldName && !duplicateNames.has(fieldName)) byName[fieldName] = edited;
    byIndex[String(index)] = edited;
  }
  const deckEdits = root[deckId] && typeof root[deckId] === "object" ? { ...root[deckId] } : {};
  if (Object.keys(byName).length || Object.keys(byIndex).length) {
    deckEdits[noteId] = { byName, byIndex, updatedAt: Date.now() };
  } else {
    delete deckEdits[noteId];
  }
  if (Object.keys(deckEdits).length) root[deckId] = deckEdits; else delete root[deckId];
  writeJson("kq.cardEdits", root);
}

function clearCardEdit(deckId, noteId) {
  const root = loadCardEdits();
  const deckKey = String(deckId);
  const deckEdits = root[deckKey] && typeof root[deckKey] === "object" ? { ...root[deckKey] } : {};
  delete deckEdits[String(noteId)];
  if (Object.keys(deckEdits).length) root[deckKey] = deckEdits; else delete root[deckKey];
  writeJson("kq.cardEdits", root);
}

function removeDeckCardEdits(deckId) {
  const root = loadCardEdits();
  delete root[String(deckId)];
  writeJson("kq.cardEdits", root);
}

function loadSeenCards() {
  const value = readJson("kq.seenCards", {});
  return value && typeof value === "object" ? value : {};
}

function isCardSeen(item, config = null) {
  return Boolean(loadSeenCards()[itemIdentity(item, config)]);
}

function markCardSeen(item, config = null) {
  const seen = loadSeenCards();
  const key = itemIdentity(item, config);
  if (!key || seen[key]) return;
  seen[key] = true;
  writeJson("kq.seenCards", seen);
}

function loadThreeCorrectProgress() {
  return readJson("kq.threeCorrectProgress", {});
}

function threeCorrectProfileKey(deck, prefs, reverse) {
  return `v1|${deck.id}|${[...prefs.qFields].sort((a, b) => a - b).join(",")}|${[...prefs.aFields].sort((a, b) => a - b).join(",")}|${reverse}`;
}

function sharedThreeCorrectProfileKey(deck) {
  return `shared-v1|${deck.id}`;
}

function getThreeCorrectProfile(profileKey) {
  const root = loadThreeCorrectProgress();
  return root[profileKey] && typeof root[profileKey] === "object" ? root[profileKey] : {};
}

function setThreeCorrectCount(profileKey, noteId, count) {
  const root = loadThreeCorrectProgress();
  const profile = root[profileKey] && typeof root[profileKey] === "object" ? { ...root[profileKey] } : {};
  const key = String(noteId);
  const clamped = Math.max(0, Math.min(THREE_CORRECT_TARGET, Number(count) || 0));
  if (clamped === 0) delete profile[key]; else profile[key] = clamped;
  if (Object.keys(profile).length) root[profileKey] = profile; else delete root[profileKey];
  writeJson("kq.threeCorrectProgress", root);
}

function resetThreeCorrectProfile(profileKey) {
  const root = loadThreeCorrectProgress();
  delete root[profileKey];
  writeJson("kq.threeCorrectProgress", root);
}

function resetAllThreeCorrectProgress() {
  localStorage.removeItem("kq.threeCorrectProgress");
}

function statKey(deckId, noteId) {
  return `${deckId}|${noteId}`;
}

function itemDeckId(item, config = null) {
  return String(item?.sourceDeckId ?? config?.deck?.id ?? "");
}

function itemIdentity(item, config = null) {
  return statKey(itemDeckId(item, config), item.noteId);
}

function todayKey() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function yesterdayKey() {
  const date = new Date();
  date.setDate(date.getDate() - 1);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function currentStreak() {
  const streak = readJson("kq.streak", { date: "", count: 0 });
  return streak.date === todayKey() || streak.date === yesterdayKey() ? streak.count : 0;
}

function bumpStreak() {
  const streak = readJson("kq.streak", { date: "", count: 0 });
  if (streak.date === todayKey()) return streak.count || 1;
  const count = streak.date === yesterdayKey() ? (streak.count || 0) + 1 : 1;
  writeJson("kq.streak", { date: todayKey(), count });
  return count;
}

function isDailyCompleted(key) {
  const completed = readJson("kq.dailyCompleted", {});
  if (Boolean(completed[key])) return true;

  // v0.1.12以前は、目標値・対象デッキ・出題設定まで完了キーに含めていた。
  // 新しい日付単位キーを確認するときだけ、当日の旧形式キーも完了として移行する。
  if (String(key).includes("|daily-global-v3")) {
    const legacyPrefix = `${todayKey()}|daily-v2|`;
    return Object.entries(completed).some(([savedKey, value]) =>
      Boolean(value) && savedKey.startsWith(legacyPrefix)
    );
  }
  return false;
}

function markDailyCompleted(key) {
  const completed = readJson("kq.dailyCompleted", {});
  completed[key] = true;
  writeJson("kq.dailyCompleted", completed);
}

const DEFAULT_DAILY_CHALLENGE = {
  goalType: "COUNT",
  targetCount: 100,
  targetMinutes: 30,
  showOverallTimer: false,
  deckIds: []
};

function loadDailyChallengeSettings() {
  const saved = readJson("kq.dailyChallengeSettings", {});
  return {
    goalType: saved.goalType === "TIME" ? "TIME" : "COUNT",
    targetCount: clampInt(saved.targetCount ?? 100, 1, 9999),
    targetMinutes: clampInt(saved.targetMinutes ?? 30, 1, 600),
    showOverallTimer: Boolean(saved.showOverallTimer),
    deckIds: Array.isArray(saved.deckIds) ? [...new Set(saved.deckIds.map(String))] : []
  };
}

function saveDailyChallengeSettings(value) {
  writeJson("kq.dailyChallengeSettings", {
    goalType: value.goalType === "TIME" ? "TIME" : "COUNT",
    targetCount: clampInt(value.targetCount, 1, 9999),
    targetMinutes: clampInt(value.targetMinutes, 1, 600),
    showOverallTimer: Boolean(value.showOverallTimer),
    deckIds: [...new Set((value.deckIds || []).map(String))]
  });
}

function globalDailyKey(_daily, _settings) {
  // デイリー完了は、その日の設定内容ではなく日付だけで保持する。
  // 完了後に目標枚数・時間・対象デッキを変更しても、当日の完了状態は維持される。
  return `${todayKey()}|daily-global-v3`;
}

function fieldPrefKey(deckId) {
  return `kq.fields.${deckId}`;
}

function loadFieldPrefs(deck) {
  const fallback = {
    qFields: [0],
    aFields: [deck.fields.length > 1 ? 1 : 0],
    mode: "QUIZ"
  };
  const saved = readJson(fieldPrefKey(deck.id), fallback);
  return {
    qFields: sanitizeIndices(saved.qFields, deck.fields.length, fallback.qFields),
    aFields: sanitizeIndices(saved.aFields, deck.fields.length, fallback.aFields),
    mode: saved.mode === "FLASHCARD" ? "FLASHCARD" : "QUIZ"
  };
}

function saveFieldPrefs(deckId, prefs) {
  writeJson(fieldPrefKey(deckId), prefs);
}

function sanitizeIndices(values, max, fallback) {
  if (!Array.isArray(values)) return fallback;
  const valid = [...new Set(values.map(Number).filter(n => Number.isInteger(n) && n >= 0 && n < max))];
  return valid.length ? valid : fallback;
}

function clampInt(value, min, max) {
  const n = Number.parseInt(value, 10);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : min;
}

function uid() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function textHtml(value) {
  return escapeHtml(value).replaceAll("\n", "<br>");
}

function cleanText(value) {
  const textarea = document.createElement("textarea");
  let text = String(value ?? "")
    .replace(/\[sound:[^\]]*\]/gi, "")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(div|p|li|tr)>/gi, "\n")
    .replace(/<[^>]*>/g, "")
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">");
  textarea.innerHTML = text;
  text = textarea.value;
  return text
    .replace(/[ \t]*\n[ \t]*/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function kataToHira(value) {
  return [...String(value ?? "")].map(ch => {
    const code = ch.charCodeAt(0);
    return code >= 0x30a1 && code <= 0x30f6 ? String.fromCharCode(code - 0x60) : ch;
  }).join("");
}

function normalizeKana(value) {
  return [...kataToHira(value)].filter(ch => (ch >= "ぁ" && ch <= "ゖ") || ch === "ー").join("");
}

function parseAnswers(raw) {
  return [...new Set(cleanText(raw)
    .split(/[、,，;；・/／\s　\n]+/)
    .map(normalizeKana)
    .filter(Boolean))];
}

function joinFields(fields, indices) {
  return [...indices].sort((a, b) => a - b)
    .map(index => cleanText(fields[index] ?? ""))
    .filter(Boolean)
    .join("\n");
}

function acceptedFrom(fields, indices) {
  return [...new Set(indices.flatMap(index => parseAnswers(fields[index] ?? "")))];
}

function compactLength(value) {
  return cleanText(value).replace(/\s/g, "").length;
}

function kanjiCount(value) {
  return [...String(value ?? "")].filter(ch => {
    const code = ch.charCodeAt(0);
    return (code >= 0x3400 && code <= 0x4dbf) ||
      (code >= 0x4e00 && code <= 0x9fff) ||
      (code >= 0xf900 && code <= 0xfaff);
  }).length;
}

function shuffle(values, random = Math.random) {
  const result = [...values];
  for (let i = result.length - 1; i > 0; i -= 1) {
    const j = Math.floor(random() * (i + 1));
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}

function hashString(value) {
  let hash = 2166136261;
  for (const ch of String(value)) {
    hash ^= ch.charCodeAt(0);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function weightedOrder(items, deckId) {
  const stats = loadCardStats();
  return items.map(item => {
    const wrong = stats[statKey(deckId, item.noteId)]?.[1] ?? 0;
    const weight = 1 + wrong;
    const random = Math.max(1e-9, Math.random());
    return { item, rank: Math.pow(random, 1 / weight) };
  }).sort((a, b) => b.rank - a.rank).map(entry => entry.item);
}

function smartReverseChoices(item, pool) {
  const targetLength = compactLength(item.question);
  const targetKanji = kanjiCount(item.question);
  const targetAnswerLength = normalizeKana(item.displayAnswer).length;
  const seen = new Set();
  const ranked = pool
    .filter(candidate => itemIdentity(candidate) !== itemIdentity(item) && candidate.question.trim() && candidate.question !== item.question)
    .filter(candidate => {
      if (seen.has(candidate.question)) return false;
      seen.add(candidate.question);
      return true;
    })
    .map(candidate => ({
      value: candidate.question,
      distance: Math.abs(compactLength(candidate.question) - targetLength) * 4 +
        Math.abs(kanjiCount(candidate.question) - targetKanji) * 6 +
        Math.abs(normalizeKana(candidate.displayAnswer).length - targetAnswerLength),
      random: Math.random()
    }))
    .sort((a, b) => a.distance - b.distance || a.random - b.random);
  const distractors = shuffle(ranked.slice(0, 12)).slice(0, 3).map(entry => entry.value);
  return shuffle([...new Set([...distractors, item.question])]);
}

function normalizeDeck(rawDeck, fallbackName = "インポートしたデッキ") {
  const fields = Array.isArray(rawDeck.fields) && rawDeck.fields.length
    ? rawDeck.fields.map((field, index) => cleanText(field) || `フィールド${index + 1}`)
    : inferFieldNames(rawDeck.notes || []);
  const notesInput = Array.isArray(rawDeck.notes) ? rawDeck.notes : [];
  const hasStableNoteIds = notesInput.length > 0 && notesInput.every(note =>
    !Array.isArray(note) && note && note.id != null && String(note.id).trim() !== ""
  );
  const notes = notesInput.map((note, index) => {
    const rawFields = Array.isArray(note) ? note : Array.isArray(note.fields) ? note.fields : [];
    const padded = Array.from({ length: fields.length }, (_, fieldIndex) => String(rawFields[fieldIndex] ?? ""));
    return {
      id: String((!Array.isArray(note) && note.id != null) ? note.id : `${Date.now()}-${index}`),
      fields: padded
    };
  }).filter(note => note.fields.some(value => cleanText(value)));
  return {
    id: rawDeck.id ? String(rawDeck.id) : uid(),
    name: cleanText(rawDeck.name || fallbackName) || fallbackName,
    fields,
    notes,
    importedAt: Date.now(),
    _importHasStableNoteIds: hasStableNoteIds
  };
}

function deckForStorage(deck, overrides = {}) {
  return {
    id: String(overrides.id ?? deck.id),
    name: cleanText(overrides.name ?? deck.name) || "インポートしたデッキ",
    fields: [...deck.fields],
    notes: deck.notes.map(note => ({ id: String(note.id), fields: [...note.fields] })),
    importedAt: Date.now()
  };
}

function duplicateNoteIds(deck) {
  const seen = new Set();
  const duplicates = new Set();
  for (const note of deck.notes) {
    const id = String(note.id);
    if (seen.has(id)) duplicates.add(id); else seen.add(id);
  }
  return [...duplicates];
}

function compareDeckUpdate(existingDeck, incomingDeck) {
  const existingById = new Map(existingDeck.notes.map(note => [String(note.id), note]));
  const incomingById = new Map(incomingDeck.notes.map(note => [String(note.id), note]));
  let matched = 0;
  let added = 0;
  let changed = 0;
  for (const [id, note] of incomingById) {
    const existing = existingById.get(id);
    if (!existing) {
      added += 1;
      continue;
    }
    matched += 1;
    if (JSON.stringify(existing.fields) !== JSON.stringify(note.fields)) changed += 1;
  }
  let removed = 0;
  for (const id of existingById.keys()) {
    if (!incomingById.has(id)) removed += 1;
  }
  return {
    matched,
    added,
    changed,
    removed,
    fieldStructureSame: JSON.stringify(existingDeck.fields) === JSON.stringify(incomingDeck.fields),
    duplicates: duplicateNoteIds(incomingDeck)
  };
}

function remapIndicesByFieldName(indices, oldFields, newFields) {
  const mapped = [];
  for (const index of indices || []) {
    const name = oldFields[Number(index)];
    const newIndex = name == null ? -1 : newFields.indexOf(name);
    if (newIndex >= 0 && !mapped.includes(newIndex)) mapped.push(newIndex);
  }
  return mapped.sort((a, b) => a - b);
}

function migrateFieldPrefsForDeckUpdate(existingDeck, incomingDeck) {
  const oldPrefs = loadFieldPrefs(existingDeck);
  const qFields = remapIndicesByFieldName(oldPrefs.qFields, existingDeck.fields, incomingDeck.fields);
  const aFields = remapIndicesByFieldName(oldPrefs.aFields, existingDeck.fields, incomingDeck.fields);
  saveFieldPrefs(existingDeck.id, {
    qFields: qFields.length ? qFields : [0],
    aFields: aFields.length ? aFields : [incomingDeck.fields.length > 1 ? 1 : 0],
    mode: oldPrefs.mode
  });
}

function migrateThreeCorrectProfilesForDeckUpdate(existingDeck, incomingDeck) {
  const root = readJson("kq.threeCorrectProgress", {});
  const prefix = `v1|${existingDeck.id}|`;
  let changed = false;
  for (const [profileKey, profile] of Object.entries({ ...root })) {
    if (!profileKey.startsWith(prefix)) continue;
    const parts = profileKey.split("|");
    if (parts.length < 5) continue;
    const oldQ = parts[2] ? parts[2].split(",").filter(Boolean).map(Number) : [];
    const oldA = parts[3] ? parts[3].split(",").filter(Boolean).map(Number) : [];
    const newQ = remapIndicesByFieldName(oldQ, existingDeck.fields, incomingDeck.fields);
    const newA = remapIndicesByFieldName(oldA, existingDeck.fields, incomingDeck.fields);
    if (!newQ.length || !newA.length) continue;
    const nextKey = `v1|${existingDeck.id}|${newQ.join(",")}|${newA.join(",")}|${parts[4]}`;
    if (nextKey === profileKey) continue;
    const target = root[nextKey] && typeof root[nextKey] === "object" ? { ...root[nextKey] } : {};
    for (const [noteId, count] of Object.entries(profile || {})) {
      target[noteId] = Math.max(Number(target[noteId]) || 0, Number(count) || 0);
    }
    root[nextKey] = target;
    delete root[profileKey];
    changed = true;
  }
  if (changed) writeJson("kq.threeCorrectProgress", root);
}

function inferFieldNames(notes) {
  const max = notes.reduce((length, note) => {
    const values = Array.isArray(note) ? note : note?.fields;
    return Math.max(length, Array.isArray(values) ? values.length : 0);
  }, 0);
  return Array.from({ length: Math.max(2, max) }, (_, index) => `フィールド${index + 1}`);
}

function parseDelimited(text, delimiter) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const ch = text[index];
    if (quoted) {
      if (ch === '"' && text[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (ch === '"') {
        quoted = false;
      } else {
        field += ch;
      }
    } else if (ch === '"') {
      quoted = true;
    } else if (ch === delimiter) {
      row.push(field);
      field = "";
    } else if (ch === "\n") {
      row.push(field.replace(/\r$/, ""));
      rows.push(row);
      row = [];
      field = "";
    } else {
      field += ch;
    }
  }
  if (field.length || row.length) {
    row.push(field.replace(/\r$/, ""));
    rows.push(row);
  }
  return rows.filter(candidate => candidate.some(value => String(value).trim()));
}

function detectDelimiter(text, filename = "") {
  const lower = filename.toLowerCase();
  if (lower.endsWith(".tsv") || lower.endsWith(".txt")) return "\t";
  if (lower.endsWith(".csv")) return ",";
  const firstLine = text.split(/\r?\n/, 1)[0] || "";
  const tabs = (firstLine.match(/\t/g) || []).length;
  const commas = (firstLine.match(/,/g) || []).length;
  const semicolons = (firstLine.match(/;/g) || []).length;
  if (tabs >= commas && tabs >= semicolons && tabs > 0) return "\t";
  if (semicolons > commas && semicolons > 0) return ";";
  return ",";
}

function genericObjectRowsToDeck(rows, name) {
  const fields = [...new Set(rows.flatMap(row => Object.keys(row)))];
  return normalizeDeck({
    name,
    fields,
    notes: rows.map((row, index) => ({ id: row.id ?? index, fields: fields.map(field => row[field] ?? "") }))
  }, name);
}


function decodeFileBytes(buffer) {
  const bytes = new Uint8Array(buffer);
  if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) {
    return new TextDecoder("utf-16le").decode(bytes.subarray(2));
  }
  if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) {
    const swapped = new Uint8Array(bytes.length - 2);
    for (let index = 2; index + 1 < bytes.length; index += 2) {
      swapped[index - 2] = bytes[index + 1];
      swapped[index - 1] = bytes[index];
    }
    return new TextDecoder("utf-16le").decode(swapped);
  }
  return new TextDecoder("utf-8").decode(bytes);
}

function readFileWithReader(file, asArrayBuffer = false) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error || new Error("FileReaderで読み込めませんでした。"));
    reader.onabort = () => reject(new Error("ファイルの読み込みが中断されました。"));
    if (asArrayBuffer) reader.readAsArrayBuffer(file);
    else reader.readAsText(file, "UTF-8");
  });
}

async function readSelectedFile(file) {
  const errors = [];
  const acceptNonEmpty = value => typeof value === "string" && value.replace(/^\uFEFF/, "").trim().length > 0;

  if (typeof file.text === "function") {
    try {
      const text = await file.text();
      if (acceptNonEmpty(text)) return text;
      errors.push("Blob.text()が空文字を返しました");
    } catch (error) {
      errors.push(`Blob.text(): ${error?.message || error}`);
    }
  }

  if (typeof file.arrayBuffer === "function") {
    try {
      const text = decodeFileBytes(await file.arrayBuffer());
      if (acceptNonEmpty(text)) return text;
      errors.push("Blob.arrayBuffer()が空データを返しました");
    } catch (error) {
      errors.push(`Blob.arrayBuffer(): ${error?.message || error}`);
    }
  }

  try {
    const result = await readFileWithReader(file, false);
    const text = typeof result === "string" ? result : "";
    if (acceptNonEmpty(text)) return text;
    errors.push("FileReader.readAsText()が空文字を返しました");
  } catch (error) {
    errors.push(`FileReader.readAsText(): ${error?.message || error}`);
  }

  try {
    const result = await readFileWithReader(file, true);
    const text = result instanceof ArrayBuffer ? decodeFileBytes(result) : "";
    if (acceptNonEmpty(text)) return text;
    errors.push("FileReader.readAsArrayBuffer()が空データを返しました");
  } catch (error) {
    errors.push(`FileReader.readAsArrayBuffer(): ${error?.message || error}`);
  }

  const sizeText = Number.isFinite(file.size) ? `${file.size}バイト` : "サイズ不明";
  const zeroSizeHelp = file.size === 0
    ? " Androidのファイル選択画面では0バイトとして渡されています。ファイル管理アプリでDownloadへコピーし直すか、別名で保存し直してから選択してください。"
    : "";
  throw new Error(`「${file.name || "選択ファイル"}」を読み取れませんでした（${sizeText}）。${zeroSizeHelp} ${errors.join(" / ")}`.trim());
}

function parseImportText(text, filename, firstRowHeaders = true) {
  const trimmed = text.replace(/^\uFEFF/, "").trim();
  if (!trimmed) throw new Error("ファイルまたは貼り付け内容が空です。");
  const baseName = filename ? filename.replace(/\.[^.]+$/, "") : "インポートしたデッキ";
  const looksJson = /^[\[{]/.test(trimmed) || filename.toLowerCase().endsWith(".json");

  if (looksJson) {
    const parsed = JSON.parse(trimmed);
    if (parsed?.format === "KanjiQuizWebBackup" && Array.isArray(parsed.decks)) {
      return { decks: parsed.decks.map(deck => normalizeDeck(deck, baseName)), backup: parsed };
    }
    if (parsed?.deck) return { decks: [normalizeDeck(parsed.deck, baseName)] };
    if (Array.isArray(parsed?.decks)) return { decks: parsed.decks.map(deck => normalizeDeck(deck, baseName)) };
    if (Array.isArray(parsed)) {
      if (!parsed.length) throw new Error("JSON配列にデータがありません。");
      if (Array.isArray(parsed[0])) {
        const rows = parsed;
        const fields = firstRowHeaders ? rows[0].map((value, index) => cleanText(value) || `フィールド${index + 1}`) : inferFieldNames(rows);
        const dataRows = firstRowHeaders ? rows.slice(1) : rows;
        return { decks: [normalizeDeck({ name: baseName, fields, notes: dataRows }, baseName)] };
      }
      if (parsed[0]?.fields) return { decks: [normalizeDeck({ name: baseName, notes: parsed }, baseName)] };
      if (typeof parsed[0] === "object") return { decks: [genericObjectRowsToDeck(parsed, baseName)] };
    }
    if (parsed && typeof parsed === "object") {
      const rows = Object.entries(parsed).map(([key, value]) => ({ key, value }));
      return { decks: [genericObjectRowsToDeck(rows, baseName)] };
    }
    throw new Error("対応していないJSON形式です。");
  }

  const delimiter = detectDelimiter(trimmed, filename);
  const rows = parseDelimited(trimmed, delimiter);
  if (!rows.length) throw new Error("表データを読み取れませんでした。");
  const width = Math.max(...rows.map(row => row.length));
  const normalizedRows = rows.map(row => Array.from({ length: width }, (_, index) => row[index] ?? ""));
  const fields = firstRowHeaders
    ? normalizedRows[0].map((value, index) => cleanText(value) || `フィールド${index + 1}`)
    : Array.from({ length: width }, (_, index) => `フィールド${index + 1}`);
  const dataRows = firstRowHeaders ? normalizedRows.slice(1) : normalizedRows;
  return { decks: [normalizeDeck({ name: baseName, fields, notes: dataRows }, baseName)] };
}

function downloadJson(filename, data) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function readerSentence(value) {
  const text = cleanText(value).replace(/[↑↓]/g, " ").replace(/\s+/g, " ").trim();
  if (!text) return "";
  return /[。！？!?]$/.test(text) ? text : `${text}。`;
}

function edgeReaderCards(deck, prefs, count) {
  const cards = deck.notes.map(note => {
    const fields = effectiveNoteFields(deck, note);
    const question = joinFields(fields, prefs.qFields);
    const answer = joinFields(fields, prefs.aFields);
    return { question, answer };
  }).filter(card => card.question || card.answer);
  return shuffle(cards).slice(0, clampInt(count, 1, 9999));
}

function edgeReaderText(cards) {
  return cards.map(card => [readerSentence(card.question), readerSentence(card.answer)]
    .filter(Boolean).join("\n"))
    .filter(Boolean)
    .join("\n\n");
}

function edgeReaderHtml(title, text) {
  const sections = text.split(/\n\n+/).map(block => {
    const paragraphs = block.split(/\n+/).filter(Boolean)
      .map(line => `<p>${escapeHtml(line)}</p>`).join("");
    return `<section>${paragraphs}</section>`;
  }).join("");
  return `<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>
html{color-scheme:light dark}body{margin:0;padding:32px 22px 64px;font-family:sans-serif;font-size:28px;line-height:1.9}main{max-width:760px;margin:0 auto}section{margin:0 0 1.8em;break-inside:avoid}p{margin:0 0 .45em}
</style>
</head>
<body><main>${sections}</main></body>
</html>`;
}

function downloadHtml(filename, html) {
  const blob = new Blob([html], { type: "text/html;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

async function refreshDecks() {
  state.decks = (await dbGetAllDecks()).sort((a, b) => a.name.localeCompare(b.name, "ja"));
}

function shell(title, body, backButton = "") {
  return `
    <main class="app-shell">
      <header class="topbar">
        <div class="row">
          ${backButton}
          <h1>${escapeHtml(title)}</h1>
        </div>
      </header>
      ${body}
    </main>`;
}

function backButton(target = "home", label = "戻る") {
  return `<button class="btn btn-ghost" type="button" data-nav="${escapeHtml(target)}">${escapeHtml(label)}</button>`;
}

function navigate(screen, options = {}) {
  const push = options.push !== false;
  if (screen !== "quiz") {
    quizInputFocused = false;
    quizViewportBaseline = 0;
    setQuizKeyboardLayout(false);
  }
  state.screen = screen;
  if (push) history.pushState({ screen }, "", location.href);
  render();
}

function render() {
  stopScreenTimersExcept(state.screen);
  switch (state.screen) {
    case "home": renderHome(); break;
    case "import": renderImport(); break;
    case "fields": renderFields(); break;
    case "settings": renderSettings(); break;
    case "dailySettings": renderDailySettings(); break;
    case "history": renderHistory(); break;
    case "cardProgress": renderCardProgress(); break;
    case "quiz": renderQuiz(); break;
    case "flash": renderFlash(); break;
    case "result": renderResult(); break;
    default: renderHome();
  }
  bindGlobalNav();
}

function bindGlobalNav() {
  // ナビゲーションはapp要素のイベント委譲で処理する。
}

function stopScreenTimersExcept(screen) {
  if (screen !== "quiz" && state.quiz) state.quiz.stopTimer();
  if (screen !== "flash" && state.flash) state.flash.stop();
}

function renderHome() {
  const daily = loadDailyChallengeSettings();
  const settings = loadSettings();
  const validDailyDecks = state.decks.filter(deck => daily.deckIds.includes(String(deck.id)));
  const dailyConfigured = validDailyDecks.length > 0;
  const dailyKey = globalDailyKey({ ...daily, deckIds: validDailyDecks.map(deck => String(deck.id)) }, settings);
  const dailyDone = dailyConfigured && isDailyCompleted(dailyKey);
  const dailySummary = dailyConfigured
    ? `${daily.goalType === "TIME" ? `毎日 ${daily.targetMinutes}分` : `毎日 ${daily.targetCount}枚`}・${validDailyDecks.length}デッキ`
    : "デッキと目標を設定してください";

  const deckCards = state.decks.length
    ? state.decks.map(deck => `
      <article class="card deck-card">
        <h3>${escapeHtml(deck.name)}</h3>
        <div class="deck-meta">${deck.notes.length}枚・${deck.fields.length}フィールド</div>
        <button class="btn btn-primary btn-wide" type="button" data-open-deck="${escapeHtml(deck.id)}">遊ぶ</button>
      </article>`).join("")
    : `<div class="card center stack">
        <strong>デッキがありません</strong>
        <span class="subtle">JSON・CSV・TSV・TXTを読み込んでください。</span>
      </div>`;

  app.innerHTML = shell("KanjiQuiz Web", `
    <div class="row-wrap space-between">
      <div class="subtle">🔥 ${currentStreak()}日連続</div>
      <div class="row-wrap">
        <button class="btn btn-ghost" type="button" data-nav="history">履歴</button>
        <button class="btn btn-ghost" type="button" data-nav="settings">設定</button>
      </div>
    </div>
    <div class="stack" style="margin-top:14px">
      <section class="card daily-card ${dailyConfigured && !dailyDone ? "daily-pending" : ""}">
        <div class="row-wrap space-between">
          <div>
            <strong>${dailyDone ? "✓ 今日のデイリー完了" : "✨ 今日のデイリー"}</strong>
            <div class="subtle small">${escapeHtml(dailySummary)}</div>
          </div>
          <button class="btn btn-ghost" id="daily-settings" type="button">設定</button>
        </div>
        <button class="btn btn-primary btn-wide" id="daily-start" type="button" style="margin-top:10px">
          ${dailyDone ? "もう一度デイリー" : "設定して始める"}
        </button>
        ${dailyDone ? `<div class="subtle small" style="margin-top:6px">完了記録はそのまま維持され、再プレイは追加の練習として扱われます。</div>` : ""}
        <div id="daily-home-error"></div>
      </section>
      <button class="btn btn-primary btn-wide" id="open-import-new" type="button">デッキを読み込む</button>
      ${deckCards}
    </div>
    <div class="footer-note">カード文字は選択可能です。Firefox上でYomitanを直接利用できます。<br>Web v${APP_VERSION}</div>
  `);

  document.getElementById("daily-settings").addEventListener("click", () => navigate("dailySettings"));
  document.getElementById("daily-start").addEventListener("click", () => {
    if (!dailyDone) {
      navigate("dailySettings");
      return;
    }
    const config = createDailyRoundConfig(settings, daily);
    if (!config.items.length) {
      document.getElementById("daily-home-error").innerHTML = `<div class="error">${escapeHtml(config.errorMessage || "出題できるカードがありません。")}</div>`;
      return;
    }
    startQuiz(config);
  });

  document.getElementById("open-import-new")?.addEventListener("click", () => {
    resetImportState();
    navigate("import");
  });

  document.querySelectorAll("[data-open-deck]").forEach(button => {
    button.addEventListener("click", () => {
      const deck = state.decks.find(candidate => candidate.id === button.dataset.openDeck);
      if (!deck) return;
      state.currentDeck = deck;
      state.fieldConfig = loadFieldPrefs(deck);
      navigate("fields");
    });
  });
}

function renderDailySettings() {
  const daily = loadDailyChallengeSettings();
  const validIds = new Set(state.decks.map(deck => String(deck.id)));
  const selected = daily.deckIds.filter(id => validIds.has(String(id)));
  const deckRows = state.decks.length
    ? state.decks.map(deck => {
        const prefs = loadFieldPrefs(deck);
        const questionNames = prefs.qFields.map(index => deck.fields[index] || `フィールド${index + 1}`).join("＋");
        const answerNames = prefs.aFields.map(index => deck.fields[index] || `フィールド${index + 1}`).join("＋");
        return `<label class="check-row card">
          <input type="checkbox" data-daily-deck="${escapeHtml(String(deck.id))}" ${selected.includes(String(deck.id)) ? "checked" : ""}>
          <span><strong>${escapeHtml(deck.name)}</strong><br><span class="subtle small">問題: ${escapeHtml(questionNames)} / 答え: ${escapeHtml(answerNames)}</span></span>
        </label>`;
      }).join("")
    : `<div class="notice">先にデッキを読み込んでください。</div>`;

  app.innerHTML = shell("デイリーチャレンジ設定", `
    <div class="stack">
      <div class="card stack">
        <div class="subtle small">選んだデッキのカードを混ぜて、KanjiQuiz内だけの「デイリーデッキ」を毎日作ります。各デッキで最後に選んだ問題側・こたえ側を使います。</div>
        <label><span>達成条件</span>
          <select class="select" id="daily-goal-type">
            <option value="COUNT" ${daily.goalType === "COUNT" ? "selected" : ""}>カード枚数</option>
            <option value="TIME" ${daily.goalType === "TIME" ? "selected" : ""}>プレイ時間</option>
          </select>
        </label>
        <label id="daily-count-wrap"><span>毎日のカード枚数</span><input class="field" id="daily-count" type="number" min="1" max="9999" value="${daily.targetCount}"></label>
        <label id="daily-time-wrap"><span>毎日のプレイ時間（分）</span><input class="field" id="daily-minutes" type="number" min="1" max="600" value="${daily.targetMinutes}"></label>
        <label class="check-row" id="daily-overall-timer-wrap">
          <input type="checkbox" id="daily-show-overall-timer" ${daily.showOverallTimer ? "checked" : ""}>
          <span>デイリー全体の残り時間を表示<br><span class="subtle small">初期値は非表示です。1問の制限時間が0以外なら、各問の残り時間は常に表示されます。</span></span>
        </label>
      </div>
      <div class="stack">
        <strong>含めるデッキ（${selected.length}件）</strong>
        ${deckRows}
      </div>
      <button class="btn btn-primary btn-wide" id="save-daily-start" type="button" ${selected.length ? "" : "disabled"}>保存して今日のデイリーを始める</button>
      <button class="btn btn-ghost btn-wide" id="save-daily" type="button">保存して戻る</button>
      <div id="daily-settings-error"></div>
    </div>
  `);

  const updateVisibility = () => {
    const type = document.getElementById("daily-goal-type").value;
    document.getElementById("daily-count-wrap").hidden = type !== "COUNT";
    document.getElementById("daily-time-wrap").hidden = type !== "TIME";
    document.getElementById("daily-overall-timer-wrap").hidden = type !== "TIME";
  };
  updateVisibility();
  document.getElementById("daily-goal-type").addEventListener("change", updateVisibility);

  const collect = () => ({
    goalType: document.getElementById("daily-goal-type").value === "TIME" ? "TIME" : "COUNT",
    targetCount: clampInt(document.getElementById("daily-count").value, 1, 9999),
    targetMinutes: clampInt(document.getElementById("daily-minutes").value, 1, 600),
    showOverallTimer: document.getElementById("daily-show-overall-timer").checked,
    deckIds: [...document.querySelectorAll("[data-daily-deck]:checked")].map(input => String(input.dataset.dailyDeck))
  });

  document.querySelectorAll("[data-daily-deck]").forEach(input => {
    input.addEventListener("change", () => {
      const next = collect();
      saveDailyChallengeSettings(next);
      renderDailySettings();
    });
  });

  document.getElementById("save-daily").addEventListener("click", () => {
    saveDailyChallengeSettings(collect());
    navigate("home");
  });
  document.getElementById("save-daily-start").addEventListener("click", () => {
    const next = collect();
    saveDailyChallengeSettings(next);
    const config = createDailyRoundConfig(loadSettings(), next);
    if (!config.items.length) {
      document.getElementById("daily-settings-error").innerHTML = `<div class="error">${escapeHtml(config.errorMessage || "出題できるカードがありません。")}</div>`;
      return;
    }
    startQuiz(config);
  });
}

function resetImportState(options = {}) {
  state.importRaw = "";
  state.importFilename = "";
  state.importDraft = null;
  state.importError = "";
  state.importMode = options.mode === "update" ? "update" : "new";
  state.importTargetDeckId = options.targetDeckId ? String(options.targetDeckId) : "";
}

function updateImportTargetSuggestion() {
  if (state.importMode === "update" || state.importTargetDeckId || state.importDraft?.decks?.length !== 1) return;
  const incoming = state.importDraft.decks[0];
  const exactMatches = state.decks.filter(deck => deck.name === incoming.name);
  if (exactMatches.length === 1) state.importTargetDeckId = String(exactMatches[0].id);
}

function renderImport() {
  const draft = state.importDraft;
  const canUpdate = draft?.decks?.length === 1 && state.decks.length > 0;
  const incomingDeck = canUpdate ? draft.decks[0] : null;
  const targetDeck = state.decks.find(deck => String(deck.id) === String(state.importTargetDeckId));
  const updateSummary = incomingDeck && targetDeck ? compareDeckUpdate(targetDeck, incomingDeck) : null;
  let preview = "";
  if (draft?.decks?.length) {
    preview = draft.decks.map(deck => `
      <div class="card stack">
        ${draft.decks.length === 1 ? `
          <label><span>デッキ名</span>
            <input class="field" id="draft-deck-name" value="${escapeHtml(state.importMode === "update" && targetDeck ? targetDeck.name : deck.name)}" maxlength="120">
          </label>` : `<strong>${escapeHtml(deck.name)}</strong>`}
        <div class="subtle">${deck.notes.length}枚・${deck.fields.length}フィールド</div>
        <div class="preview-wrap">
          <table>
            <thead><tr>${deck.fields.map(field => `<th>${escapeHtml(field)}</th>`).join("")}</tr></thead>
            <tbody>
              ${deck.notes.slice(0, 5).map(note => `<tr>${note.fields.map(value => `<td>${textHtml(cleanText(value))}</td>`).join("")}</tr>`).join("")}
            </tbody>
          </table>
        </div>
        ${deck.notes.length > 5 ? `<div class="subtle small">先頭5枚を表示</div>` : ""}
      </div>`).join("");
  }

  const updateOptions = state.decks.map(deck =>
    `<option value="${escapeHtml(String(deck.id))}" ${String(deck.id) === String(state.importTargetDeckId) ? "selected" : ""}>${escapeHtml(deck.name)}（${deck.notes.length}枚）</option>`
  ).join("");

  const updatePanel = canUpdate ? `
    <div class="card stack">
      <strong>保存方法</strong>
      <label class="row"><input type="radio" name="import-mode" value="new" ${state.importMode !== "update" ? "checked" : ""}><span>新しいデッキとして追加</span></label>
      <label class="row"><input type="radio" name="import-mode" value="update" ${state.importMode === "update" ? "checked" : ""}><span>既存デッキを更新し、カードの進捗を維持</span></label>
      ${state.importMode === "update" ? `
        <label><span>更新する既存デッキ</span>
          <select class="select" id="import-target-deck">
            <option value="">選択してください</option>
            ${updateOptions}
          </select>
        </label>
        ${targetDeck && updateSummary ? `
          <div class="notice stack">
            <div><strong>更新内容</strong></div>
            <div class="grid-2 small">
              <div>進捗を維持する既存カード：${updateSummary.matched}枚</div>
              <div>新しく追加するカード：${updateSummary.added}枚</div>
              <div>内容が変わる既存カード：${updateSummary.changed}枚</div>
              <div>新データから消えたカード：${updateSummary.removed}枚</div>
            </div>
            <div class="small">同じカードIDの成功回数・苦手度・出題済み状態を維持します。新規カードは進捗0から開始します。消えたカードの進捗は内部に残るため、同じIDで再追加すると復元されます。</div>
            ${!updateSummary.fieldStructureSame ? `<div class="small">フィールド構成が変わっています。問題側・回答側の選択は同名フィールドへ自動移行します。</div>` : ""}
            ${updateSummary.duplicates.length ? `<div class="error">同じカードIDが重複しています：${escapeHtml(updateSummary.duplicates.slice(0, 5).join(", "))}${updateSummary.duplicates.length > 5 ? "…" : ""}</div>` : ""}
          </div>` : `<div class="subtle small">更新先を選ぶと、追加・変更・削除されるカード数を確認できます。</div>`}
      ` : ""}
    </div>` : "";

  const updateUnsafe = state.importMode === "update" && incomingDeck && !incomingDeck._importHasStableNoteIds;
  const confirmLabel = state.importMode === "update" && targetDeck
    ? `「${escapeHtml(targetDeck.name)}」を更新`
    : `${draft?.decks?.length || 0}デッキを保存`;

  app.innerHTML = shell("デッキを読み込む", `
    <div class="stack">
      <div class="card stack">
        <label><span>ファイル</span>
          <input class="field" id="import-file" type="file" accept=".json,.csv,.tsv,.txt,application/json,text/csv,text/tab-separated-values,text/plain">
        </label>
        <label class="row">
          <input id="first-row-header" type="checkbox" checked>
          <span>先頭行をフィールド名として使う（CSV・TSV）</span>
        </label>
        <div class="subtle small">AnkiDroidからはAndroid版の「Web版用JSONを保存」を使ってください。更新取り込みでは、AnkiDroid由来のカードIDを使って既存進捗を維持します。</div>
        ${state.importFilename ? `<div class="subtle small">選択中: ${escapeHtml(state.importFilename)}${state.importRaw ? `・読み取り済み ${new Blob([state.importRaw]).size}バイト` : ""}</div>` : ""}
      </div>

      <div class="card stack">
        <label><span>またはテキストを貼り付け</span>
          <textarea class="textarea" id="import-text" placeholder="漢字<TAB>読み&#10;薔薇<TAB>ばら">${escapeHtml(state.importRaw)}</textarea>
        </label>
        <button class="btn btn-ghost" id="parse-paste" type="button">貼り付け内容を解析</button>
        <button class="btn btn-ghost" id="load-sample" type="button">サンプルデッキを読み込む</button>
      </div>

      ${state.importError ? `<div class="error">${escapeHtml(state.importError)}</div>` : ""}
      ${preview}
      ${updatePanel}
      ${updateUnsafe ? `<div class="error">このファイルには安定したカードIDがありません。CSV・TSVでは既存カードと新規カードを安全に判別できないため、更新取り込みはできません。Android版から書き出したJSONを使用してください。</div>` : ""}
      ${draft?.decks?.length ? `<button class="btn btn-primary btn-wide" id="confirm-import" type="button" ${updateUnsafe ? "disabled" : ""}>${confirmLabel}</button>` : ""}
      <button class="btn btn-ghost btn-wide" type="button" data-nav="home">キャンセル</button>
    </div>
  `);

  const headerCheck = document.getElementById("first-row-header");
  document.getElementById("import-file").addEventListener("change", async event => {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      state.importFilename = file.name;
      state.importRaw = await readSelectedFile(file);
      state.importDraft = parseImportText(state.importRaw, file.name, headerCheck.checked);
      state.importError = "";
      updateImportTargetSuggestion();
    } catch (error) {
      state.importDraft = null;
      state.importError = `読み込みに失敗しました：${error.message}`;
    }
    renderImport();
  });

  document.getElementById("parse-paste").addEventListener("click", () => {
    state.importRaw = document.getElementById("import-text").value;
    state.importFilename = "pasted.tsv";
    try {
      state.importDraft = parseImportText(state.importRaw, state.importFilename, headerCheck.checked);
      state.importError = "";
      updateImportTargetSuggestion();
    } catch (error) {
      state.importDraft = null;
      state.importError = `解析に失敗しました：${error.message}`;
    }
    renderImport();
  });

  document.getElementById("load-sample").addEventListener("click", async () => {
    try {
      const response = await fetch("./sample-deck.json");
      state.importRaw = await response.text();
      state.importFilename = "sample-deck.json";
      state.importDraft = parseImportText(state.importRaw, state.importFilename, true);
      state.importError = "";
      updateImportTargetSuggestion();
    } catch (error) {
      state.importError = `サンプルを読み込めませんでした：${error.message}`;
    }
    renderImport();
  });

  document.querySelectorAll('input[name="import-mode"]').forEach(input => {
    input.addEventListener("change", () => {
      state.importMode = input.value === "update" ? "update" : "new";
      if (state.importMode === "update" && !state.importTargetDeckId && state.decks.length === 1) {
        state.importTargetDeckId = String(state.decks[0].id);
      }
      renderImport();
    });
  });

  document.getElementById("import-target-deck")?.addEventListener("change", event => {
    state.importTargetDeckId = event.target.value;
    renderImport();
  });

  document.getElementById("confirm-import")?.addEventListener("click", async () => {
    try {
      if (state.importDraft.decks.length === 1) {
        state.importDraft.decks[0].name = cleanText(document.getElementById("draft-deck-name").value) || state.importDraft.decks[0].name;
      }
      if (state.importMode === "update") {
        if (state.importDraft.decks.length !== 1) throw new Error("更新取り込みは1回につき1デッキのみ対応しています。");
        const incoming = state.importDraft.decks[0];
        const existing = state.decks.find(deck => String(deck.id) === String(state.importTargetDeckId));
        if (!existing) throw new Error("更新する既存デッキを選択してください。");
        if (!incoming._importHasStableNoteIds) throw new Error("カードIDがないため安全に更新できません。Android版から書き出したJSONを使用してください。");
        const summary = compareDeckUpdate(existing, incoming);
        if (summary.duplicates.length) throw new Error("同じカードIDが重複しているため更新できません。");
        const message = `「${existing.name}」を更新します。\n\n進捗維持: ${summary.matched}枚\n新規追加: ${summary.added}枚\n内容変更: ${summary.changed}枚\nデッキから削除: ${summary.removed}枚\n\n既存の成功回数・苦手度・出題済み状態・KanjiQuiz内のカード編集は維持されます。続行しますか？`;
        if (!confirm(message)) return;
        migrateThreeCorrectProfilesForDeckUpdate(existing, incoming);
        migrateFieldPrefsForDeckUpdate(existing, incoming);
        await dbPutDeck(deckForStorage(incoming, { id: existing.id }));
      } else {
        for (const deck of state.importDraft.decks) {
          if (!deck.notes.length) throw new Error(`「${deck.name}」にカードがありません。`);
          const idAlreadyExists = state.decks.some(existing => String(existing.id) === String(deck.id));
          const storageId = state.importDraft.backup ? deck.id : (idAlreadyExists ? uid() : deck.id);
          await dbPutDeck(deckForStorage(deck, { id: storageId }));
        }
      }
      if (state.importDraft.backup?.appData) restoreAppData(state.importDraft.backup.appData);
      await refreshDecks();
      resetImportState();
      navigate("home");
    } catch (error) {
      state.importError = `保存に失敗しました：${error.message}`;
      renderImport();
    }
  });
}

function restoreAppData(appData) {
  const allowed = ["settings", "history", "cardStats", "seenCards", "streak", "dailyCompleted", "threeCorrectProgress", "dailyChallengeSettings", "cardEdits"];
  for (const key of allowed) {
    if (appData[key] != null) writeJson(`kq.${key}`, appData[key]);
  }
}

function gameModeOptions(selected) {
  return Object.entries(GAME_MODES)
    .filter(([key]) => key !== "MASTERY" && key !== "DAILY")
    .map(([key, label]) => `<option value="${key}" ${selected === key ? "selected" : ""}>${escapeHtml(label)}</option>`)
    .join("");
}

function renderFields() {
  const deck = state.currentDeck;
  if (!deck) return navigate("home", { push: false });
  const prefs = state.fieldConfig || loadFieldPrefs(deck);
  const settings = loadSettings();
  const sample = deck.notes[0] ? effectiveNoteFields(deck, deck.notes[0]) : [];
  const dailyKey = makeDailyKey(deck, prefs.qFields, prefs.aFields, settings.reverse);
  const dailyDone = settings.gameMode === "DAILY" && isDailyCompleted(dailyKey);

  const questionFieldRows = deck.fields.map((name, index) => `
    <label class="check-row">
      <input type="checkbox" data-q-field="${index}" ${prefs.qFields.includes(index) ? "checked" : ""}>
      <span><strong>${escapeHtml(name)}</strong><br><span class="field-sample">${textHtml(cleanText(sample[index] ?? ""))}</span></span>
    </label>`).join("");

  const answerFieldRows = deck.fields.map((name, index) => `
    <label class="check-row">
      <input type="checkbox" data-a-field="${index}" ${prefs.aFields.includes(index) ? "checked" : ""}>
      <span><strong>${escapeHtml(name)}</strong><br><span class="field-sample">${textHtml(cleanText(sample[index] ?? ""))}</span></span>
    </label>`).join("");

  app.innerHTML = shell(deck.name, `
    <div class="stack">
      <div class="card stack">
        <strong>表示方法</strong>
        <div class="chips">
          <button class="chip ${prefs.mode === "QUIZ" ? "active" : ""}" type="button" data-mode="QUIZ">クイズ</button>
          <button class="chip ${prefs.mode === "FLASHCARD" ? "active" : ""}" type="button" data-mode="FLASHCARD">めくり</button>
        </div>
      </div>

      <div class="card stack">
        <strong>フィールド選択</strong>
        <div class="field-side-grid">
          <section class="field-side">
            <div class="field-side-title">問題側</div>
            <div class="subtle small">問題として表示するフィールド</div>
            <div class="checkbox-list">${questionFieldRows}</div>
          </section>
          <section class="field-side">
            <div class="field-side-title">こたえ側</div>
            <div class="subtle small">正解として使うフィールド</div>
            <div class="checkbox-list">${answerFieldRows}</div>
          </section>
        </div>
      </div>

      <div class="card stack">
        <div class="grid-2">
          <label><span>ゲーム形式</span>
            <select class="select" id="game-mode">${gameModeOptions(settings.gameMode)}</select>
          </label>
          <label><span>出題の向き</span>
            <select class="select" id="reverse-mode">
              <option value="false" ${!settings.reverse ? "selected" : ""}>問題 → 読みを入力</option>
              <option value="true" ${settings.reverse ? "selected" : ""}>読み → 漢字を4択</option>
            </select>
          </label>
          <label><span>出題数（-1ですべて）</span>
            <input class="field" id="question-count" type="number" min="-1" max="9999" value="${settings.count}">
          </label>
          <label><span>1問の制限時間（0で無制限）</span>
            <input class="field" id="time-limit" type="number" min="0" max="600" value="${settings.timeLimitSec}">
          </label>
          <label><span>最大挑戦回数</span>
            <input class="field" id="max-attempts" type="number" min="1" max="99" value="${settings.maxAttempts}">
          </label>
          <label><span>正誤表示時間（秒）</span>
            <input class="field" id="feedback-sec" type="number" min="0.1" max="60" step="0.1" value="${settings.feedbackDeci / 10}">
          </label>
          <label><span>ゲーム画面の文字サイズ（16〜96px）</span>
            <input class="field" id="game-font-size" type="number" min="16" max="96" value="${settings.gameFontSize}">
          </label>
        </div>
        <label class="row"><input id="new-only" type="checkbox" ${settings.newOnly ? "checked" : ""}><span>新規のみ（まだ回答結果が確定していないカードだけ）</span></label>
        <label class="row"><input id="weak-priority" type="checkbox" ${settings.weakPriority ? "checked" : ""}><span>苦手カードを優先</span></label>
        <label class="row"><input id="auto-advance" type="checkbox" ${settings.autoAdvance ? "checked" : ""}><span>正誤表示後に自動で次へ</span></label>
        <label class="row"><input id="shared-three-all" type="checkbox" ${settings.sharedThreeCorrectAllModes ? "checked" : ""}><span>成功回数を全モードで共通にする（3回で全モードから除外）</span></label>
        ${dailyDone ? `<div class="notice">今日の正式なデイリーチャレンジは完了済みです。再挑戦できますが履歴には追加されません。</div>` : ""}
        <div id="three-correct-panel" class="notice" hidden></div>
      </div>

      <div class="card stack">
        <strong>Microsoft Edgeの読み上げ用</strong>
        <div class="subtle small">選んだ枚数の問題と答えを1つの長い文章にまとめます。長文ページにはカード内容以外を表示しません。</div>
        <label><span>まとめるカード枚数</span>
          <input class="field" id="edge-reader-count" type="number" min="1" max="9999" value="${settings.edgeReaderCount}">
        </label>
        <button class="btn btn-primary btn-wide" id="open-edge-reader" type="button">Edge読み上げ用ページを開く</button>
        <button class="btn btn-ghost btn-wide" id="download-edge-reader" type="button">Edge用HTMLを保存</button>
        <div class="subtle small">KanjiQuizをEdgeで開いている場合は上のボタンを使います。Firefoxから作る場合はHTMLを保存し、そのファイルをEdgeで開いてください。</div>
      </div>

      <div id="field-error"></div>
      <button class="btn btn-ghost btn-wide" id="open-card-progress" type="button">カード別の成功回数を見る</button>
      <button class="btn btn-primary btn-wide" id="start-game" type="button">スタート</button>
      <div class="grid-2">
        <button class="btn btn-ghost" id="export-deck" type="button">このデッキをJSON保存</button>
        <button class="btn btn-ghost" id="update-deck-import" type="button">更新したデッキを再取り込み</button>
      </div>
      <button class="btn btn-danger btn-wide" id="delete-deck" type="button">このデッキを削除</button>
      <button class="btn btn-ghost btn-wide" type="button" data-nav="home">デッキ一覧へ</button>
    </div>
  `);

  const updateThreeCorrectPanel = () => {
    const panel = document.getElementById("three-correct-panel");
    const selectedMode = document.getElementById("game-mode").value;
    const shared = document.getElementById("shared-three-all").checked;
    if (!shared && selectedMode !== "THREE_CORRECT") {
      panel.hidden = true;
      panel.innerHTML = "";
      return;
    }
    const reverse = document.getElementById("reverse-mode").value === "true";
    const profileKey = shared
      ? sharedThreeCorrectProfileKey(deck)
      : threeCorrectProfileKey(deck, prefs, reverse);
    const progress = getThreeCorrectProfile(profileKey);
    const usable = makeUsableItems(deck, prefs, reverse);
    const completed = usable.filter(item => (progress[String(item.noteId)] || 0) >= THREE_CORRECT_TARGET).length;
    panel.hidden = false;
    panel.innerHTML = `
      <div><strong>${shared ? "全モード共通の達成" : "累積達成"} ${completed}/${usable.length}</strong></div>
      <div class="small">正解で+1、不正解で-1。3に到達したカードは${shared ? "全モード" : "3回正解モード"}から除外されます。</div>
      <button class="btn btn-ghost btn-wide" id="reset-three-current" type="button">${shared ? "このデッキの共通回数" : "この設定の累積回数"}をリセット</button>`;
    document.getElementById("reset-three-current").addEventListener("click", () => {
      if (!confirm(shared
        ? "このデッキの全モード共通の成功回数をリセットしますか？"
        : "このデッキ・フィールド・出題方向の累積正解数をリセットしますか？")) return;
      resetThreeCorrectProfile(profileKey);
      updateThreeCorrectPanel();
    });
  };

  document.getElementById("game-mode").addEventListener("change", updateThreeCorrectPanel);
  document.getElementById("reverse-mode").addEventListener("change", updateThreeCorrectPanel);
  document.getElementById("shared-three-all").addEventListener("change", updateThreeCorrectPanel);

  document.querySelectorAll("[data-mode]").forEach(button => {
    button.addEventListener("click", () => {
      prefs.mode = button.dataset.mode;
      saveFieldPrefs(deck.id, prefs);
      renderFields();
    });
  });

  document.querySelectorAll("[data-q-field]").forEach(input => {
    input.addEventListener("change", () => {
      prefs.qFields = toggleIndex(prefs.qFields, Number(input.dataset.qField), input.checked);
      saveFieldPrefs(deck.id, prefs);
      updateThreeCorrectPanel();
    });
  });
  document.querySelectorAll("[data-a-field]").forEach(input => {
    input.addEventListener("change", () => {
      prefs.aFields = toggleIndex(prefs.aFields, Number(input.dataset.aField), input.checked);
      saveFieldPrefs(deck.id, prefs);
      updateThreeCorrectPanel();
    });
  });

  const buildEdgeReaderDocument = () => {
    if (!prefs.qFields.length || !prefs.aFields.length) {
      document.getElementById("field-error").innerHTML =
        `<div class="error">問題側とこたえ側を、それぞれ1つ以上選んでください。</div>`;
      return null;
    }
    const count = clampInt(document.getElementById("edge-reader-count").value, 1, 9999);
    const nextSettings = { ...loadSettings(), edgeReaderCount: count };
    saveSettings(nextSettings);
    const cards = edgeReaderCards(deck, prefs, count);
    if (!cards.length) {
      document.getElementById("field-error").innerHTML =
        `<div class="error">Edge読み上げ用にまとめられるカードがありません。</div>`;
      return null;
    }
    const text = edgeReaderText(cards);
    return { text, html: edgeReaderHtml(deck.name, text) };
  };

  document.getElementById("open-edge-reader").addEventListener("click", () => {
    const documentData = buildEdgeReaderDocument();
    if (!documentData) return;
    localStorage.setItem("kq.edgeReaderText", documentData.text);
    localStorage.setItem("kq.edgeReaderTitle", deck.name);
    window.open("./reader.html", "_blank");
  });

  document.getElementById("download-edge-reader").addEventListener("click", () => {
    const documentData = buildEdgeReaderDocument();
    if (!documentData) return;
    downloadHtml(`${safeFilename(deck.name)}-edge-read-aloud.html`, documentData.html);
  });

  document.getElementById("open-card-progress").addEventListener("click", () => {
    const nextSettings = collectFieldSettings(settings);
    saveSettings(nextSettings);
    state.fieldConfig = prefs;
    saveFieldPrefs(deck.id, prefs);
    if (!prefs.qFields.length || !prefs.aFields.length) {
      document.getElementById("field-error").innerHTML =
        `<div class="error">問題側とこたえ側を、それぞれ1つ以上選んでください。</div>`;
      return;
    }
    navigate("cardProgress");
  });

  document.getElementById("start-game").addEventListener("click", () => {
    const nextSettings = collectFieldSettings(settings);
    saveSettings(nextSettings);
    state.fieldConfig = prefs;
    saveFieldPrefs(deck.id, prefs);
    const errorBox = document.getElementById("field-error");
    if (!prefs.qFields.length || !prefs.aFields.length) {
      errorBox.innerHTML = `<div class="error">問題側とこたえ側を、それぞれ1つ以上選んでください。</div>`;
      return;
    }
    if (prefs.mode === "FLASHCARD") {
      const items = buildFlashItems(deck, prefs, nextSettings);
      if (!items.length) {
        errorBox.innerHTML = `<div class="error">この組み合わせでは表示できるカードがありません。</div>`;
        return;
      }
      startFlash(items, nextSettings);
      return;
    }
    const config = createRoundConfig(deck, prefs, nextSettings);
    if (!config.items.length) {
      const message = nextSettings.newOnly
        ? "この条件に新規カードがありません。新規のみをオフにしてください。"
        : nextSettings.gameMode === "WEAK_CHALLENGE"
          ? "苦手語がまだありません。まず通常モードなどで問題を解いてください。"
          : nextSettings.gameMode === "THREE_CORRECT"
            ? "この設定では、すべてのカードが累積3回正解に到達しています。"
            : nextSettings.sharedThreeCorrectAllModes
              ? "全モード共通の成功回数が3に到達しているため、出題できるカードがありません。"
              : "この組み合わせでは問題を作れませんでした。";
      errorBox.innerHTML = `<div class="error">${escapeHtml(message)}</div>`;
      return;
    }
    startQuiz(config);
  });

  updateThreeCorrectPanel();

  document.getElementById("export-deck").addEventListener("click", () => {
    const exportedDeck = {
      ...deck,
      notes: deck.notes.map(note => ({
        id: String(note.id),
        fields: effectiveNoteFields(deck, note)
      }))
    };
    downloadJson(`${safeFilename(deck.name)}.kanjiquiz.json`, {
      format: "KanjiQuizWebDeck",
      version: 1,
      deck: exportedDeck
    });
  });

  document.getElementById("update-deck-import").addEventListener("click", () => {
    resetImportState({ mode: "update", targetDeckId: deck.id });
    navigate("import");
  });

  document.getElementById("delete-deck").addEventListener("click", async () => {
    if (!confirm(`「${deck.name}」をWeb版から削除しますか？\nAnkiDroid側のデータは変更されません。`)) return;
    await dbDeleteDeck(deck.id);
    localStorage.removeItem(fieldPrefKey(deck.id));
    removeDeckCardEdits(deck.id);
    state.currentDeck = null;
    state.fieldConfig = null;
    await refreshDecks();
    navigate("home");
  });
}

function toggleIndex(values, index, checked) {
  const set = new Set(values);
  if (checked) set.add(index); else set.delete(index);
  return [...set].sort((a, b) => a - b);
}

function collectFieldSettings(base) {
  return {
    ...base,
    gameMode: document.getElementById("game-mode").value,
    reverse: document.getElementById("reverse-mode").value === "true",
    count: clampInt(document.getElementById("question-count").value, -1, 9999),
    timeLimitSec: clampInt(document.getElementById("time-limit").value, 0, 600),
    maxAttempts: clampInt(document.getElementById("max-attempts").value, 1, 99),
    feedbackDeci: clampInt(Math.round(Number(document.getElementById("feedback-sec").value) * 10), 1, 600),
    gameFontSize: clampInt(document.getElementById("game-font-size").value, 16, 96),
    weakPriority: document.getElementById("weak-priority").checked,
    autoAdvance: document.getElementById("auto-advance").checked,
    sharedThreeCorrectAllModes: document.getElementById("shared-three-all").checked,
    newOnly: document.getElementById("new-only").checked
  };
}

function makeUsableItems(deck, prefs, reverse) {
  return deck.notes.map(note => {
    const fields = effectiveNoteFields(deck, note);
    return {
      noteId: note.id,
      sourceDeckId: String(deck.id),
      sourceDeckName: deck.name,
      fields,
      qFields: [...prefs.qFields],
      aFields: [...prefs.aFields],
      question: joinFields(fields, prefs.qFields),
      displayAnswer: joinFields(fields, prefs.aFields),
      accepted: acceptedFrom(fields, prefs.aFields)
    };
  }).filter(item => reverse
    ? item.question.trim() && item.displayAnswer.trim()
    : item.question.trim() && item.accepted.length);
}

function makeDailyKey(deck, qFields, aFields, reverse) {
  return `${todayKey()}|${deck.id}|${[...qFields].sort().join(",")}|${[...aFields].sort().join(",")}|${reverse}`;
}

function selectRoundItems(pool, gameMode, settings, deck, prefs) {
  switch (gameMode) {
    case "NORMAL": {
      const ordered = settings.weakPriority ? weightedOrder(pool, deck.id) : shuffle(pool);
      return settings.count < 0 ? ordered : ordered.slice(0, settings.count);
    }
    case "SURVIVAL":
    case "TIME_ATTACK":
      return settings.weakPriority ? weightedOrder(pool, deck.id) : shuffle(pool);
    case "THREE_CORRECT": {
      const ordered = settings.weakPriority ? weightedOrder(pool, deck.id) : shuffle(pool);
      return settings.count < 0 ? ordered : ordered.slice(0, settings.count);
    }
    case "WEAK_CHALLENGE": {
      const stats = loadCardStats();
      return pool
        .map(item => {
          const stat = stats[statKey(deck.id, item.noteId)] || [0, 0];
          return { item, seen: stat[0] || 0, wrong: stat[1] || 0 };
        })
        .filter(entry => entry.wrong > 0)
        .sort((a, b) => b.wrong - a.wrong || a.seen - b.seen)
        .slice(0, 10)
        .map(entry => entry.item);
    }
    case "DAILY": {
      const random = mulberry32(hashString(makeDailyKey(deck, prefs.qFields, prefs.aFields, settings.reverse)));
      return shuffle([...pool].sort((a, b) => String(a.noteId).localeCompare(String(b.noteId))), random).slice(0, 10);
    }
    case "MASTERY":
      return pool;
    default:
      return [];
  }
}

function createRoundConfig(deck, prefs, settings, options = {}) {
  const reverse = options.reverse ?? settings.reverse;
  const pool = makeUsableItems(deck, prefs, reverse);
  const gameMode = options.gameMode ?? settings.gameMode;
  const threeCorrectKey = settings.sharedThreeCorrectAllModes
    ? sharedThreeCorrectProfileKey(deck)
    : gameMode === "THREE_CORRECT"
      ? threeCorrectProfileKey(deck, prefs, reverse)
      : null;
  const savedThreeCorrect = threeCorrectKey ? getThreeCorrectProfile(threeCorrectKey) : {};
  const afterThreeCorrect = threeCorrectKey
    ? pool.filter(item => (savedThreeCorrect[String(item.noteId)] || 0) < THREE_CORRECT_TARGET)
    : pool;
  const eligiblePool = settings.newOnly
    ? afterThreeCorrect.filter(item => !isCardSeen(item, { deck }))
    : afterThreeCorrect;
  const selected = options.fixedItems ?? selectRoundItems(eligiblePool, gameMode, { ...settings, reverse }, deck, prefs);
  const items = uniqueBy(selected, item => itemIdentity(item, { deck }));
  return {
    deck,
    prefs: structuredClone(prefs),
    items,
    gameMode,
    reverse,
    timeLimitSec: settings.timeLimitSec,
    autoAdvance: settings.autoAdvance,
    feedbackDeci: settings.feedbackDeci,
    weakPriority: settings.weakPriority,
    maxAttempts: settings.maxAttempts,
    gameFontSize: settings.gameFontSize,
    pauseWhileSelecting: settings.pauseWhileSelecting,
    choicePool: pool,
    dailyKey: gameMode === "DAILY" ? makeDailyKey(deck, prefs.qFields, prefs.aFields, reverse) : null,
    threeCorrectKey,
    sharedThreeCorrectAllModes: Boolean(settings.sharedThreeCorrectAllModes),
    newOnly: Boolean(settings.newOnly),
    threeCorrectKeyByCard: threeCorrectKey
      ? Object.fromEntries(items.map(item => [itemIdentity(item, { deck }), threeCorrectKey]))
      : {},
    initialThreeCorrectCounts: threeCorrectKey
      ? Object.fromEntries(items.map(item => [itemIdentity(item, { deck }), savedThreeCorrect[String(item.noteId)] || 0]))
      : {},
    dailyGoalType: null,
    dailyTargetValue: 0
  };
}

function createDailyRoundConfig(settings, daily) {
  const selectedDecks = state.decks.filter(deck => daily.deckIds.includes(String(deck.id)));
  const allItems = selectedDecks.flatMap(deck => makeUsableItems(deck, loadFieldPrefs(deck), settings.reverse));
  const progressCache = {};
  const afterShared = settings.sharedThreeCorrectAllModes
    ? allItems.filter(item => {
        const deck = selectedDecks.find(candidate => String(candidate.id) === String(item.sourceDeckId));
        if (!deck) return false;
        const profileKey = sharedThreeCorrectProfileKey(deck);
        progressCache[profileKey] ||= getThreeCorrectProfile(profileKey);
        return (progressCache[profileKey][String(item.noteId)] || 0) < THREE_CORRECT_TARGET;
      })
    : allItems;
  const eligible = settings.newOnly
    ? afterShared.filter(item => !isCardSeen(item))
    : afterShared;
  const key = globalDailyKey(daily, settings);
  const random = mulberry32(hashString(key));
  const ordered = shuffle(
    uniqueBy(eligible, item => itemIdentity(item)).sort((a, b) => itemIdentity(a).localeCompare(itemIdentity(b))),
    random
  );
  const countTarget = clampInt(daily.targetCount, 1, 9999);
  const insufficientForCount = daily.goalType === "COUNT" && ordered.length < countTarget;
  const items = insufficientForCount
    ? []
    : daily.goalType === "COUNT"
      ? ordered.slice(0, countTarget)
      : ordered;
  const threeCorrectKeyByCard = {};
  const initialThreeCorrectCounts = {};
  if (settings.sharedThreeCorrectAllModes) {
    for (const item of items) {
      const deck = selectedDecks.find(candidate => String(candidate.id) === String(item.sourceDeckId));
      if (!deck) continue;
      const profileKey = sharedThreeCorrectProfileKey(deck);
      progressCache[profileKey] ||= getThreeCorrectProfile(profileKey);
      const cardKey = itemIdentity(item);
      threeCorrectKeyByCard[cardKey] = profileKey;
      initialThreeCorrectCounts[cardKey] = progressCache[profileKey][String(item.noteId)] || 0;
    }
  }
  return {
    deck: { id: "__daily__", name: "デイリーデッキ", fields: [], notes: [] },
    prefs: null,
    items,
    gameMode: "DAILY",
    reverse: settings.reverse,
    timeLimitSec: settings.timeLimitSec,
    autoAdvance: settings.autoAdvance,
    feedbackDeci: settings.feedbackDeci,
    weakPriority: settings.weakPriority,
    maxAttempts: settings.maxAttempts,
    gameFontSize: settings.gameFontSize,
    pauseWhileSelecting: settings.pauseWhileSelecting,
    choicePool: allItems,
    dailyKey: key,
    threeCorrectKey: null,
    sharedThreeCorrectAllModes: Boolean(settings.sharedThreeCorrectAllModes),
    newOnly: Boolean(settings.newOnly),
    threeCorrectKeyByCard,
    initialThreeCorrectCounts,
    dailyGoalType: daily.goalType,
    dailyTargetValue: daily.goalType === "TIME"
      ? clampInt(daily.targetMinutes, 1, 600) * 60
      : countTarget,
    showDailyOverallTimer: Boolean(daily.showOverallTimer),
    errorMessage: insufficientForCount
      ? `デイリー対象カードが${ordered.length}枚しかありません。目標の${countTarget}枚以上になるよう、デッキを追加するか条件を変更してください。`
      : ""
  };
}

function uniqueBy(values, keyFn) {
  const seen = new Set();
  return values.filter(value => {
    const key = keyFn(value);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function buildFlashItems(deck, prefs, settings) {
  const items = shuffle(deck.notes).map(note => {
    const fields = effectiveNoteFields(deck, note);
    return {
      noteId: note.id,
      sourceDeckId: String(deck.id),
      sourceDeckName: deck.name,
      fields,
      qFields: [...prefs.qFields],
      aFields: [...prefs.aFields],
      question: joinFields(fields, prefs.qFields),
      answer: joinFields(fields, prefs.aFields),
      displayAnswer: joinFields(fields, prefs.aFields),
      accepted: acceptedFrom(fields, prefs.aFields)
    };
  }).filter(item => item.question || item.answer);
  return settings.count < 0 ? items : items.slice(0, settings.count);
}


function sourceDeckAndNote(item) {
  const deck = state.decks.find(candidate => String(candidate.id) === String(item?.sourceDeckId));
  const note = deck?.notes.find(candidate => String(candidate.id) === String(item?.noteId));
  return { deck, note };
}

function refreshRuntimeItem(item, deck, note) {
  if (!item || String(item.sourceDeckId) !== String(deck.id) || String(item.noteId) !== String(note.id)) return;
  const fields = effectiveNoteFields(deck, note);
  item.fields = fields;
  const qFields = Array.isArray(item.qFields) ? item.qFields : [];
  const aFields = Array.isArray(item.aFields) ? item.aFields : [];
  item.question = joinFields(fields, qFields);
  item.displayAnswer = joinFields(fields, aFields);
  item.answer = item.displayAnswer;
  item.accepted = acceptedFrom(fields, aFields);
}

function refreshRuntimeCard(deck, note) {
  const candidates = new Set();
  const addAll = values => {
    if (!Array.isArray(values)) return;
    values.forEach(value => value && candidates.add(value));
  };
  const quiz = state.quiz;
  if (quiz) {
    addAll(quiz.baseItems);
    addAll(quiz.cycleItems);
    addAll(quiz.masteryQueue);
    addAll(quiz.threeCorrectQueue);
    addAll(quiz.config?.items);
    addAll(quiz.config?.choicePool);
    quiz.logs?.forEach(log => log?.item && candidates.add(log.item));
  }
  addAll(state.flash?.items);
  candidates.forEach(item => refreshRuntimeItem(item, deck, note));
}

function closeCardEditor() {
  document.getElementById("card-editor-overlay")?.remove();
}

function openCardEditor(item, { onSaved = () => {}, onClosed = () => {} } = {}) {
  closeCardEditor();
  const { deck, note } = sourceDeckAndNote(item);
  if (!deck || !note) {
    alert("このカードの元デッキを確認できないため編集できません。");
    onClosed();
    return;
  }
  const effective = effectiveNoteFields(deck, note);
  const hasEdit = Boolean(cardEditEntry(deck.id, note.id));
  const overlay = document.createElement("div");
  overlay.id = "card-editor-overlay";
  overlay.className = "modal-backdrop";
  overlay.innerHTML = `
    <div class="modal card-editor-modal" role="dialog" aria-modal="true" aria-labelledby="card-editor-title">
      <h3 id="card-editor-title">カードを編集</h3>
      <p class="small subtle">編集内容はKanjiQuiz内だけに保存されます。AnkiDroidのカードは変更されません。</p>
      <div class="card-editor-fields">
        ${deck.fields.map((fieldName, index) => `
          <label class="card-editor-field">
            <span>${escapeHtml(fieldName || `フィールド${index + 1}`)}</span>
            <textarea class="field card-editor-textarea" data-edit-field="${index}" rows="3">${escapeHtml(effective[index] ?? "")}</textarea>
          </label>`).join("")}
      </div>
      <div id="card-editor-message"></div>
      <div class="grid-2">
        <button class="btn btn-ghost" id="cancel-card-edit" type="button">キャンセル</button>
        <button class="btn btn-primary" id="save-card-edit" type="button">保存</button>
      </div>
      <button class="btn btn-danger btn-wide" id="reset-card-edit" type="button" ${hasEdit ? "" : "disabled"}>KanjiQuiz内の編集を取り消す</button>
    </div>`;
  document.body.appendChild(overlay);

  const finishClose = () => {
    closeCardEditor();
    onClosed();
  };
  bindImmediateQuizAction(document.getElementById("cancel-card-edit"), finishClose);
  bindImmediateQuizAction(document.getElementById("save-card-edit"), () => {
    const values = Array.from({ length: deck.fields.length }, (_, index) =>
      document.querySelector(`[data-edit-field="${index}"]`)?.value ?? ""
    );
    saveCardEdit(deck, note, values);
    refreshRuntimeCard(deck, note);
    closeCardEditor();
    onSaved();
    onClosed();
  });
  bindImmediateQuizAction(document.getElementById("reset-card-edit"), () => {
    if (!confirm("このカードに保存したKanjiQuiz内の編集を取り消し、AnkiDroidから読み込んだ内容へ戻しますか？")) return;
    clearCardEdit(deck.id, note.id);
    refreshRuntimeCard(deck, note);
    closeCardEditor();
    onSaved();
    onClosed();
  });
}

class QuizSession {
  constructor(config) {
    this.config = config;
    this.baseItems = config.items;
    this.cycleItems = [...config.items];
    this.masteryQueue = shuffle(config.items);
    this.threeCorrectQueue = shuffle(config.items);
    this.threeCorrectCounts = new Map(Object.entries(config.initialThreeCorrectCounts || {}));
    this.index = 0;
    this.attemptNumber = 1;
    this.questionSerial = 0;
    this.remaining = config.timeLimitSec;
    this.globalLimit = config.gameMode === "DAILY" && config.dailyGoalType === "TIME"
      ? Math.max(1, Number(config.dailyTargetValue) || 1)
      : TIME_ATTACK_SEC;
    this.globalRemaining = this.globalLimit;
    this.phase = "ASKING";
    this.lastCorrect = false;
    this.lastWasManualPass = false;
    this.lastGained = 0;
    this.lastTimeBonus = 0;
    this.score = 0;
    this.combo = 0;
    this.maxCombo = 0;
    this.lives = 3;
    this.logs = [];
    this.startedAt = Date.now();
    this.ended = false;
    this.paused = false;
    this.selectionPaused = false;
    this.timerId = null;
    this.feedbackDeadline = null;
    this.lastTick = performance.now();
    this.retryNotice = "";
  }

  get isTimeAttack() { return this.config.gameMode === "TIME_ATTACK"; }
  get isSurvival() { return this.config.gameMode === "SURVIVAL"; }
  get isMastery() { return this.config.gameMode === "MASTERY"; }
  get isThreeCorrect() { return this.config.gameMode === "THREE_CORRECT"; }
  get isDailyTime() { return this.config.gameMode === "DAILY" && this.config.dailyGoalType === "TIME"; }
  get isDailyCount() { return this.config.gameMode === "DAILY" && this.config.dailyGoalType === "COUNT"; }
  get isCycling() { return this.isTimeAttack || this.isSurvival || this.isDailyTime; }
  get perQuestionTimer() { return !this.isTimeAttack && this.config.timeLimitSec > 0; }

  get item() {
    if (this.isMastery || this.isDailyCount) return this.masteryQueue[0];
    if (this.isThreeCorrect) return this.threeCorrectQueue[0];
    return this.cycleItems[this.index];
  }

  get promptText() { return this.config.reverse ? this.item.displayAnswer : this.item.question; }
  get answerText() { return this.config.reverse ? this.item.question : this.item.displayAnswer; }
  get choices() { return this.config.reverse ? smartReverseChoices(this.item, this.config.choicePool) : []; }

  cardKey(item = this.item) {
    return itemIdentity(item, this.config);
  }

  getThreeCorrectCount(item = this.item) {
    return Number(this.threeCorrectCounts.get(this.cardKey(item)) || 0);
  }

  setThreeCorrectCount(item, count) {
    const updated = Math.max(0, Math.min(THREE_CORRECT_TARGET, Number(count) || 0));
    const cardKey = this.cardKey(item);
    this.threeCorrectCounts.set(cardKey, updated);
    const profileKey = this.config.threeCorrectKeyByCard?.[cardKey] || this.config.threeCorrectKey;
    if (profileKey) setThreeCorrectCount(profileKey, item.noteId, updated);
    return updated;
  }

  startTimer() {
    this.stopTimer();
    this.lastTick = performance.now();
    this.timerId = window.setInterval(() => this.tick(), 100);
  }

  stopTimer() {
    if (this.timerId != null) window.clearInterval(this.timerId);
    this.timerId = null;
  }

  tick() {
    if (this.ended) return this.stopTimer();
    const now = performance.now();
    const delta = Math.min(0.5, Math.max(0, (now - this.lastTick) / 1000));
    this.lastTick = now;
    if (this.paused || this.selectionPaused || document.hidden) {
      if (this.phase === "FEEDBACK" && this.feedbackDeadline != null) {
        this.feedbackDeadline += delta * 1000;
      }
      updateQuizTimerUi(this);
      return;
    }

    if (this.isTimeAttack || this.isDailyTime) {
      this.globalRemaining = Math.max(0, this.globalRemaining - delta);
      if (this.globalRemaining <= 0) {
        finishQuiz(this);
        return;
      }
    }

    if (this.phase === "ASKING" && this.perQuestionTimer) {
      this.remaining = Math.max(0, this.remaining - delta);
      if (this.remaining <= 0) {
        this.judge(false, "");
        return;
      }
    }

    if (this.phase === "FEEDBACK" && this.config.autoAdvance && this.feedbackDeadline != null && now >= this.feedbackDeadline) {
      this.goNext();
      return;
    }
    updateQuizTimerUi(this);
  }

  resetForNextQuestion() {
    this.questionSerial += 1;
    this.attemptNumber = 1;
    this.retryNotice = "";
    this.lastWasManualPass = false;
    this.remaining = this.config.timeLimitSec;
    this.lastTimeBonus = 0;
    this.phase = "ASKING";
    this.feedbackDeadline = null;
    renderQuizQuestion(this);
  }

  retrySameQuestion() {
    this.attemptNumber += 1;
    const remainingAttempts = this.config.maxAttempts - this.attemptNumber + 1;
    this.retryNotice = `不正解。あと${remainingAttempts}回挑戦できます。`;
    this.questionSerial += 1;
    // 同じ1問への再挑戦なので、残り時間は引き継ぐ。
    this.lastGained = 0;
    this.lastTimeBonus = 0;
    this.phase = "ASKING";
    renderQuizQuestion(this);
  }

  activeCyclePool() {
    const answered = this.config.newOnly
      ? new Set(this.logs.map(log => this.cardKey(log.item)))
      : new Set();
    return this.baseItems.filter(item =>
      (!this.config.sharedThreeCorrectAllModes || this.getThreeCorrectCount(item) < THREE_CORRECT_TARGET) &&
      (!this.config.newOnly || !answered.has(this.cardKey(item)))
    );
  }

  reshuffleAvoidingLast(last) {
    const next = shuffle(this.activeCyclePool());
    if (next.length > 1 && this.cardKey(next[0]) === this.cardKey(last)) {
      const swapIndex = next.findIndex(candidate => this.cardKey(candidate) !== this.cardKey(last));
      if (swapIndex > 0) [next[0], next[swapIndex]] = [next[swapIndex], next[0]];
    }
    return next;
  }

  judge(correct, recorded, manualPass = false) {
    if (this.phase !== "ASKING" || this.ended) return;
    const canRetry = !correct && String(recorded).trim() && this.attemptNumber < this.config.maxAttempts;
    if (canRetry) {
      this.combo = 0;
      this.retrySameQuestion();
      return;
    }

    this.retryNotice = "";
    this.lastWasManualPass = manualPass;
    if (correct) {
      this.combo += 1;
      this.maxCombo = Math.max(this.maxCombo, this.combo);
      const speed = this.perQuestionTimer && this.config.timeLimitSec > 0
        ? Math.round((this.remaining / this.config.timeLimitSec) * 50)
        : 0;
      this.lastGained = 50 + speed + (this.combo - 1) * 5;
      this.score += this.lastGained;
      if (this.config.threeCorrectKey || Object.keys(this.config.threeCorrectKeyByCard || {}).length) {
        this.setThreeCorrectCount(this.item, this.getThreeCorrectCount(this.item) + 1);
      }
      if (this.isTimeAttack) {
        const comboBonus = this.combo % 5 === 0 ? 2 : 0;
        this.lastTimeBonus = 1 + comboBonus;
        this.globalRemaining = Math.min(TIME_ATTACK_SEC, this.globalRemaining + this.lastTimeBonus);
      }
    } else {
      this.combo = 0;
      this.lastGained = 0;
      this.lastTimeBonus = 0;
      if (this.isSurvival) this.lives -= 1;
      if (this.config.threeCorrectKey || Object.keys(this.config.threeCorrectKeyByCard || {}).length) {
        this.setThreeCorrectCount(this.item, this.getThreeCorrectCount(this.item) - 1);
      }
    }
    this.lastCorrect = correct;
    this.logs.push({ item: this.item, correct, input: recorded });
    this.phase = "FEEDBACK";
    this.feedbackDeadline = manualPass ? null : performance.now() + this.config.feedbackDeci * 100;
    renderQuizFeedback(this);
  }

  goNext() {
    if (this.phase !== "FEEDBACK" || this.ended) return;
    if (this.isSurvival && this.lives <= 0) return finishQuiz(this);

    if (this.isMastery || this.isDailyCount) {
      const answered = this.masteryQueue.shift();
      if (!this.lastCorrect) this.masteryQueue.push(answered);
      if (!this.masteryQueue.length) return finishQuiz(this);
      this.resetForNextQuestion();
      return;
    }

    if (this.isThreeCorrect) {
      const answered = this.threeCorrectQueue.shift();
      const count = this.getThreeCorrectCount(answered);
      if (count < THREE_CORRECT_TARGET) this.threeCorrectQueue.push(answered);
      if (!this.threeCorrectQueue.length) return finishQuiz(this);
      this.resetForNextQuestion();
      return;
    }

    if (this.config.sharedThreeCorrectAllModes &&
        this.getThreeCorrectCount(this.item) >= THREE_CORRECT_TARGET) {
      const currentId = this.cardKey(this.item);
      this.cycleItems = this.cycleItems.filter(item => this.cardKey(item) !== currentId);
      if (!this.cycleItems.length) return finishQuiz(this);
      if (this.index < this.cycleItems.length) {
        this.resetForNextQuestion();
        return;
      }
      if (this.isCycling) {
        this.cycleItems = this.reshuffleAvoidingLast(this.item);
        if (!this.cycleItems.length) return finishQuiz(this);
        this.index = 0;
        this.resetForNextQuestion();
        return;
      }
      return finishQuiz(this);
    }

    if (this.index + 1 < this.cycleItems.length) {
      this.index += 1;
      this.resetForNextQuestion();
      return;
    }

    if (this.isCycling) {
      this.cycleItems = this.reshuffleAvoidingLast(this.item);
      if (!this.cycleItems.length) return finishQuiz(this);
      this.index = 0;
      this.resetForNextQuestion();
    } else {
      finishQuiz(this);
    }
  }
}

function startQuiz(config) {
  state.quiz?.stopTimer();
  state.quiz = new QuizSession(config);
  navigate("quiz");
}

function renderQuiz() {
  const session = state.quiz;
  if (!session || session.ended) return navigate("home", { push: false });
  app.innerHTML = `
    <main class="app-shell quiz-shell" style="${gameFontStyle(session.config.gameFontSize)}">
      <div class="quiz-header">
        <div>
          <div id="quiz-status"></div>
          <div id="quiz-combo" class="combo"></div>
          <div id="quiz-attempt" class="subtle small"></div>
          <div id="quiz-retry" class="retry-notice small"></div>
          <div id="selection-pause" class="subtle small"></div>
        </div>
        <div class="row row-wrap font-controls">
          <span id="quiz-score" class="score"></span>
          <button class="btn btn-ghost font-step" id="font-smaller" type="button" aria-label="文字を小さく">A−</button>
          <span id="font-size-label" class="small">${session.config.gameFontSize}px</span>
          <button class="btn btn-ghost font-step" id="font-larger" type="button" aria-label="文字を大きく">A＋</button>
          <button class="btn btn-ghost" id="quit-quiz" type="button">やめる</button>
        </div>
      </div>
      <div id="quiz-progress"></div>
      <section class="prompt-area" id="quiz-main"></section>
      <section class="answer-controls" id="quiz-controls"></section>
    </main>
    <div id="quiz-modal"></div>`;
  const changeFontSize = delta => {
    const next = clampInt(session.config.gameFontSize + delta, 16, 96);
    if (next === session.config.gameFontSize) return;
    session.config.gameFontSize = next;
    const settings = loadSettings();
    saveSettings({ ...settings, gameFontSize: next });
    applyGameFontStyle(document.querySelector(".quiz-shell"), next);
    document.getElementById("font-size-label").textContent = `${next}px`;
    document.getElementById("font-smaller").disabled = next <= 16;
    document.getElementById("font-larger").disabled = next >= 96;
  };
  document.getElementById("font-smaller").addEventListener("click", () => changeFontSize(-4));
  document.getElementById("font-larger").addEventListener("click", () => changeFontSize(4));
  document.getElementById("font-smaller").disabled = session.config.gameFontSize <= 16;
  document.getElementById("font-larger").disabled = session.config.gameFontSize >= 96;
  document.getElementById("quit-quiz").addEventListener("click", () => showQuitQuizModal(session));
  renderQuizQuestion(session);
  session.startTimer();
}

function bindImmediateQuizAction(button, action) {
  let activePointerId = null;
  let startX = 0;
  let startY = 0;
  let moved = false;

  button.addEventListener("pointerdown", event => {
    if (event.pointerType !== "touch" && event.pointerType !== "pen") return;
    // Firefox Androidではボタンへフォーカスが移るとキーボードが閉じ、
    // pointerup前にレイアウトが動く。既定動作を止め、同じボタンで
    // pointerupを受け取ってから1回だけ処理する。
    event.preventDefault();
    event.stopPropagation();
    activePointerId = event.pointerId;
    startX = event.clientX;
    startY = event.clientY;
    moved = false;
    try {
      button.setPointerCapture(event.pointerId);
    } catch {
      // Pointer capture未対応でも通常のpointerupで処理する。
    }
  });

  button.addEventListener("pointermove", event => {
    if (event.pointerId !== activePointerId) return;
    if (Math.hypot(event.clientX - startX, event.clientY - startY) > 18) moved = true;
  });

  button.addEventListener("pointerup", event => {
    if (event.pointerId !== activePointerId) return;
    event.preventDefault();
    event.stopPropagation();
    activePointerId = null;
    try {
      button.releasePointerCapture(event.pointerId);
    } catch {
      // 既にDOMから外れている場合などは何もしない。
    }
    if (moved) return;

    // pointerup後に生成される互換clickが、描画し直された「次へ」に
    // 当たって二重処理されるのを防ぐ。
    suppressedQuizClick = {
      until: performance.now() + 450,
      x: event.clientX,
      y: event.clientY
    };
    action();
  });

  button.addEventListener("pointercancel", event => {
    if (event.pointerId === activePointerId) activePointerId = null;
  });

  button.addEventListener("click", () => action());
}

// touch/pen操作の直後にブラウザーが生成する互換clickを、描画後の別ボタンへ
// 誤送信しないよう、同じ位置の最初のclickだけをキャプチャ段階で破棄する。
document.addEventListener("click", event => {
  const guard = suppressedQuizClick;
  if (!guard) return;
  if (performance.now() > guard.until) {
    suppressedQuizClick = null;
    return;
  }
  const dx = Math.abs((Number.isFinite(event.clientX) ? event.clientX : guard.x) - guard.x);
  const dy = Math.abs((Number.isFinite(event.clientY) ? event.clientY : guard.y) - guard.y);
  if (dx <= 36 && dy <= 36) {
    event.preventDefault();
    event.stopImmediatePropagation();
    suppressedQuizClick = null;
  }
}, true);

function renderQuizQuestion(session) {
  if (state.screen !== "quiz" || session.ended) return;
  markCardSeen(session.item, session.config);
  quizInputFocused = false;
  quizViewportBaseline = Math.max(quizViewportBaseline, currentQuizViewportHeight());
  setQuizKeyboardLayout(false);
  updateQuizHeaderUi(session);
  const main = document.getElementById("quiz-main");
  const controls = document.getElementById("quiz-controls");
  main.innerHTML = `
    <div class="selectable prompt-text" id="selectable-prompt">${textHtml(session.promptText)}</div>
    <button class="btn btn-ghost card-edit-button" id="edit-current-card" type="button">編集</button>`;
  if (session.config.reverse) {
    const choices = session.choices;
    controls.innerHTML = `
      ${choices.map((choice, index) => `<button class="btn btn-primary btn-choice" type="button" data-choice="${index}">${textHtml(choice.replaceAll("\n", " "))}</button>`).join("")}
      <button class="btn btn-ghost" id="pass-question" type="button">パス →</button>`;
    controls.querySelectorAll("[data-choice]").forEach(button => {
      button.addEventListener("click", () => {
        const choice = choices[Number(button.dataset.choice)];
        session.judge(choice === session.item.question, choice);
      });
    });
  } else {
    controls.innerHTML = `
      <div class="answer-row">
        <input class="field" id="answer-input" type="text" inputmode="text" autocomplete="off" autocapitalize="off" placeholder="よみを ひらがなで入力">
        <button class="btn btn-primary" id="submit-answer" type="button">回答</button>
      </div>
      <button class="btn btn-ghost" id="pass-question" type="button">パス →</button>`;
    const input = document.getElementById("answer-input");
    const submit = () => {
      const normalized = normalizeKana(input.value);
      if (!normalized) return;
      session.judge(session.item.accepted.includes(normalized), input.value);
    };
    bindImmediateQuizAction(document.getElementById("submit-answer"), submit);
    input.addEventListener("focus", () => {
      quizInputFocused = true;
      quizViewportBaseline = Math.max(quizViewportBaseline, currentQuizViewportHeight());
      setTimeout(refreshQuizKeyboardLayout, 80);
      setTimeout(refreshQuizKeyboardLayout, 300);
    });
    input.addEventListener("blur", () => {
      quizInputFocused = false;
      refreshQuizKeyboardLayout();
    });
    input.addEventListener("keydown", event => {
      if (event.key === "Enter") {
        event.preventDefault();
        submit();
      }
    });
  }
  bindImmediateQuizAction(document.getElementById("pass-question"), () => session.judge(false, "", true));
  bindCurrentCardEditor(session);
  installSelectionPause(session);
  updateQuizTimerUi(session);
}

function bindCurrentCardEditor(session) {
  const button = document.getElementById("edit-current-card");
  if (!button) return;
  button.addEventListener("click", () => {
    const wasPaused = session.paused;
    session.paused = true;
    const rerender = () => {
      if (!state.quiz || state.quiz !== session || session.ended) return;
      if (session.phase === "FEEDBACK") renderQuizFeedback(session);
      else renderQuizQuestion(session);
    };
    openCardEditor(session.item, {
      onSaved: rerender,
      onClosed: () => {
        if (!state.quiz || state.quiz !== session || session.ended) return;
        session.paused = wasPaused;
        session.lastTick = performance.now();
        updateQuizTimerUi(session);
      }
    });
  });
}

function installSelectionPause(session) {
  session.selectionPaused = false;
  const label = document.getElementById("selection-pause");
  if (label) label.textContent = "";
}

function renderQuizFeedback(session) {
  if (state.screen !== "quiz" || session.ended) return;
  quizInputFocused = false;
  setQuizKeyboardLayout(false);
  updateQuizHeaderUi(session);
  const log = session.logs.at(-1);
  const title = session.lastCorrect ? "⭕ 正解！" : log.input ? "❌ 不正解" : "⏰ 時間切れ・パス";
  const currentThree = session.getThreeCorrectCount(session.item);
  document.getElementById("quiz-main").innerHTML = `
    <div class="feedback">
      <div class="feedback-title ${session.lastCorrect ? "correct" : "wrong"}">${title}</div>
      <div class="selectable feedback-question">${textHtml(session.promptText)}</div>
      <div class="selectable feedback-answer">${textHtml(session.answerText)}</div>
      ${!session.lastCorrect && log.input ? `<div class="wrong small">あなたの解答：${escapeHtml(log.input)}</div>` : ""}
      ${session.lastCorrect && session.attemptNumber > 1 ? `<div class="subtle small">${session.attemptNumber}回目の挑戦で正解</div>` : ""}
      ${(session.config.threeCorrectKey || Object.keys(session.config.threeCorrectKeyByCard || {}).length) ? `<div class="${currentThree >= THREE_CORRECT_TARGET ? "correct" : "subtle"}">
        ${currentThree >= THREE_CORRECT_TARGET
          ? (session.config.sharedThreeCorrectAllModes ? "成功3回：全モードから除外されます" : "累積3回正解：以後は出題されません")
          : `このカードの成功回数 ${currentThree}/${THREE_CORRECT_TARGET}`}
      </div>` : ""}
      ${session.lastCorrect ? `<div class="correct"><strong>+${session.lastGained}</strong></div>` : ""}
      ${session.isTimeAttack && session.lastTimeBonus > 0 ? `<div class="combo">⏱ +${session.lastTimeBonus}秒${session.lastTimeBonus >= 3 ? "（5コンボボーナス）" : ""}</div>` : ""}
    </div>`;

  const finishAfter = shouldFinishAfterFeedback(session);
  const imageSearchUrl = `https://www.google.com/search?tbm=isch&q=${encodeURIComponent(session.promptText.trim())}`;
  document.getElementById("quiz-controls").innerHTML = `
    <button class="btn btn-ghost btn-wide" id="edit-current-card" type="button">カードを編集</button>
    <a class="btn btn-ghost btn-wide" id="google-image-search" href="${escapeHtml(imageSearchUrl)}" target="_blank" rel="noopener noreferrer">Google画像で検索</a>
    <button class="btn btn-primary btn-wide" id="next-question" type="button">${finishAfter ? "結果を見る" : "次へ"}</button>`;
  bindCurrentCardEditor(session);
  document.getElementById("next-question").addEventListener("click", () => session.goNext());
  session.selectionPaused = false;
  updateQuizTimerUi(session);
}

function shouldFinishAfterFeedback(session) {
  if (session.isSurvival && session.lives <= 0) return true;
  if ((session.isMastery || session.isDailyCount) && session.lastCorrect && session.masteryQueue.length === 1) return true;
  if (session.isThreeCorrect && session.lastCorrect &&
      session.getThreeCorrectCount(session.item) >= THREE_CORRECT_TARGET &&
      session.threeCorrectQueue.length === 1) return true;
  if (session.config.sharedThreeCorrectAllModes &&
      session.getThreeCorrectCount(session.item) >= THREE_CORRECT_TARGET &&
      session.activeCyclePool().length === 0) return true;
  if (!session.isCycling && !session.isMastery && !session.isDailyCount && !session.isThreeCorrect && session.index + 1 >= session.cycleItems.length) return true;
  return false;
}

function updateQuizHeaderUi(session) {
  const status = document.getElementById("quiz-status");
  const combo = document.getElementById("quiz-combo");
  const attempt = document.getElementById("quiz-attempt");
  const retry = document.getElementById("quiz-retry");
  const score = document.getElementById("quiz-score");
  if (!status) return;
  if (session.isDailyTime || session.isDailyCount) {
    status.textContent = `デイリー・回答 ${session.logs.length}問`;
  }
  else if (session.isTimeAttack) status.textContent = `残り ${Math.ceil(session.globalRemaining)}秒・${session.logs.length}問`;
  else if (session.isSurvival) status.textContent = `${"❤".repeat(Math.max(0, session.lives))}${"♡".repeat(Math.max(0, 3 - session.lives))}・${session.logs.length}問`;
  else if (session.isMastery) status.textContent = `残り ${session.masteryQueue.length}語`;
  else if (session.isThreeCorrect) {
    const total = [...session.threeCorrectCounts.values()].reduce((sum, value) => sum + value, 0);
    status.textContent = `残り ${session.threeCorrectQueue.length}語・累積 ${total}/${session.baseItems.length * THREE_CORRECT_TARGET}`;
  } else status.textContent = `${session.index + 1} / ${session.cycleItems.length}`;
  status.className = (session.isTimeAttack && session.globalRemaining < 10) ||
    (session.isDailyTime && session.config.showDailyOverallTimer && session.globalRemaining < 60) ? "wrong" : "";
  combo.textContent = session.combo >= 2 ? `🔥 ${session.combo} COMBO` : "";
  attempt.textContent = session.phase === "ASKING" && session.config.maxAttempts > 1
    ? `挑戦 ${session.attemptNumber} / ${session.config.maxAttempts}` : "";
  retry.textContent = session.retryNotice;
  score.textContent = `SCORE ${session.score}`;
}

function updateQuizTimerUi(session) {
  updateQuizHeaderUi(session);
  const container = document.getElementById("quiz-progress");
  if (!container) return;

  const rows = [];
  if (session.isDailyTime && session.config.showDailyOverallTimer) {
    const percent = Math.max(0, Math.min(100, (session.globalRemaining / session.globalLimit) * 100));
    rows.push(`
      <div class="timer-row">
        <div class="timer-label"><span>今日のデイリー</span><span>残り ${Math.ceil(session.globalRemaining)}秒</span></div>
        <div class="progress daily-time-progress ${session.globalRemaining < 60 ? "danger" : ""}" role="progressbar" aria-label="デイリー全体の残り時間" aria-valuemin="0" aria-valuemax="${session.globalLimit}" aria-valuenow="${Math.ceil(session.globalRemaining)}"><div style="width:${percent}%"></div></div>
      </div>`);
  } else if (session.isDailyCount) {
    const completed = session.logs.filter(log => log.correct).length;
    const percent = session.config.dailyTargetValue > 0
      ? Math.max(0, Math.min(100, (completed / session.config.dailyTargetValue) * 100))
      : 0;
    rows.push(`
      <div class="timer-row">
        <div class="timer-label"><span>今日のデイリー</span><span>${Math.min(completed, session.config.dailyTargetValue)} / ${session.config.dailyTargetValue}枚</span></div>
        <div class="progress" role="progressbar" aria-label="デイリー正解枚数" aria-valuemin="0" aria-valuemax="${session.config.dailyTargetValue}" aria-valuenow="${Math.min(completed, session.config.dailyTargetValue)}"><div style="width:${percent}%"></div></div>
      </div>`);
  } else if (session.isTimeAttack) {
    const percent = Math.max(0, Math.min(100, (session.globalRemaining / TIME_ATTACK_SEC) * 100));
    rows.push(`<div class="progress ${session.globalRemaining < 10 ? "danger" : ""}"><div style="width:${percent}%"></div></div>`);
  }

  const showQuestionTimer = session.perQuestionTimer && !session.isTimeAttack;
  if (showQuestionTimer) {
    const percent = Math.max(0, Math.min(100, (session.remaining / session.config.timeLimitSec) * 100));
    const questionBar = `<div class="progress question-time-progress ${session.remaining < session.config.timeLimitSec * .3 ? "danger" : ""}" role="progressbar" aria-label="現在の問題の残り時間" aria-valuemin="0" aria-valuemax="${session.config.timeLimitSec}" aria-valuenow="${Math.ceil(session.remaining)}"><div style="width:${percent}%"></div></div>`;
    rows.push(session.isDailyTime || session.isDailyCount
      ? `<div class="timer-row"><div class="timer-label"><span>現在の問題</span><span>残り ${Math.ceil(session.remaining)}秒</span></div>${questionBar}</div>`
      : questionBar);
  }

  container.innerHTML = rows.length ? `<div class="timer-stack">${rows.join("")}</div>` : "";
}

function showQuitQuizModal(session) {
  session.paused = true;
  const modalRoot = document.getElementById("quiz-modal");
  modalRoot.innerHTML = `
    <div class="modal-backdrop">
      <div class="modal">
        <h3>ホームに戻る</h3>
        <p>プレイ中です。ホームに戻ると今回の結果は記録されません。</p>
        <div class="grid-2">
          <button class="btn btn-danger" id="confirm-quit" type="button">戻る</button>
          <button class="btn btn-primary" id="cancel-quit" type="button">続ける</button>
        </div>
      </div>
    </div>`;
  document.getElementById("confirm-quit").addEventListener("click", () => {
    session.stopTimer();
    state.quiz = null;
    navigate("home");
  });
  document.getElementById("cancel-quit").addEventListener("click", () => {
    session.paused = false;
    session.lastTick = performance.now();
    modalRoot.innerHTML = "";
  });
}

function finishQuiz(session) {
  if (session.ended) return;
  session.ended = true;
  session.stopTimer();
  const durationSec = Math.max(0, Math.round((Date.now() - session.startedAt) / 1000));
  const result = recordRoundResult(session.config, {
    logs: session.logs,
    score: session.score,
    maxCombo: session.maxCombo,
    durationSec,
    dailyGoalCompleted: session.config.gameMode !== "DAILY" ||
      (session.isDailyTime
        ? session.globalRemaining <= 0
        : session.logs.filter(log => log.correct).length >= session.config.dailyTargetValue)
  });
  state.lastResult = result;
  state.quiz = null;
  navigate("result");
}

function recordRoundResult(config, round) {
  const stats = loadCardStats();
  const beforeWrong = {};
  for (const log of round.logs) {
    const key = statKey(itemDeckId(log.item, config), log.item.noteId);
    beforeWrong[key] = stats[key]?.[1] ?? 0;
    const current = stats[key] || [0, 0];
    current[0] += 1;
    current[1] = log.correct ? Math.max(0, current[1] - 1) : Math.min(10, current[1] + 2);
    stats[key] = current;
  }
  saveCardStats(stats);

  const completed = round.logs.length > 0;
  const dailyAlready = config.gameMode === "DAILY" && config.dailyKey && isDailyCompleted(config.dailyKey);
  const dailyGoalSatisfied = config.gameMode !== "DAILY" || Boolean(round.dailyGoalCompleted);
  const shouldRecord = completed && dailyGoalSatisfied && !dailyAlready;
  if (completed && dailyGoalSatisfied) bumpStreak();
  if (shouldRecord) {
    addHistory({
      timeMillis: Date.now(),
      deckId: config.deck.id,
      deck: config.deck.name,
      score: round.score,
      correct: round.logs.filter(log => log.correct).length,
      total: round.logs.length,
      gameMode: config.gameMode,
      reverse: config.reverse,
      weakPriority: config.weakPriority,
      maxCombo: round.maxCombo,
      durationSec: round.durationSec
    });
    if (config.gameMode === "DAILY" && config.dailyKey) markDailyCompleted(config.dailyKey);
  }

  const attemptedKeys = [...new Set(round.logs.map(log => statKey(itemDeckId(log.item, config), log.item.noteId)))];
  const masteredCount = attemptedKeys.filter(key => (beforeWrong[key] || 0) > 0 && (stats[key]?.[1] || 0) === 0).length;
  let threeCorrectCompleted = 0;
  let threeCorrectTotal = 0;
  if (config.gameMode === "THREE_CORRECT" && config.threeCorrectKey) {
    const progress = getThreeCorrectProfile(config.threeCorrectKey);
    const allItems = makeUsableItems(config.deck, config.prefs, config.reverse);
    threeCorrectTotal = allItems.length;
    threeCorrectCompleted = allItems.filter(item => (progress[String(item.noteId)] || 0) >= THREE_CORRECT_TARGET).length;
  }
  return {
    config,
    ...round,
    historyRecorded: shouldRecord,
    masteredCount,
    threeCorrectCompleted,
    threeCorrectTotal
  };
}

function renderResult() {
  const result = state.lastResult;
  if (!result) return navigate("home", { push: false });
  const correct = result.logs.filter(log => log.correct).length;
  const total = result.logs.length;
  const accuracy = total ? Math.round((correct / total) * 100) : 0;
  const missed = uniqueBy(result.logs.filter(log => !log.correct).map(log => log.item), item => itemIdentity(item, result.config));
  const missedDetails = result.logs.filter(log => !log.correct).map(log => `
    <div class="missed-item">
      <div class="selectable"><strong>${textHtml(log.item.question)}</strong></div>
      <div class="selectable correct">${textHtml(log.item.displayAnswer)}</div>
      ${log.input ? `<div class="wrong small">あなたの解答：${escapeHtml(log.input)}</div>` : ""}
    </div>`).join("");

  app.innerHTML = shell("結果", `
    <div class="stack">
      <div class="card stack center">
        <strong>${escapeHtml(GAME_MODES[result.config.gameMode])}</strong>
        <div class="metric-grid">
          <div class="metric"><span>正答率</span><strong>${accuracy}%</strong></div>
          <div class="metric"><span>スコア</span><strong>${result.score}</strong></div>
          <div class="metric"><span>最大コンボ</span><strong>${result.maxCombo}</strong></div>
        </div>
        <div class="subtle">${correct}/${total}問正解・${result.durationSec}秒</div>
        ${result.masteredCount > 0 ? `<div class="success">苦手語を${result.masteredCount}件克服しました。</div>` : ""}
        ${result.config.gameMode === "THREE_CORRECT" ? `<div class="${result.threeCorrectTotal > 0 && result.threeCorrectCompleted >= result.threeCorrectTotal ? "success" : "notice"}">
          ${result.threeCorrectTotal > 0 && result.threeCorrectCompleted >= result.threeCorrectTotal
            ? "この設定の全カードが累積3回正解に到達しました。"
            : `累積達成 ${result.threeCorrectCompleted}/${result.threeCorrectTotal}`}
        </div>` : ""}
        ${!result.historyRecorded && result.config.gameMode === "DAILY" ? `<div class="notice">${result.dailyGoalCompleted
          ? "今日のデイリー履歴はすでに記録済みです。"
          : "デイリーの達成条件に届かなかったため、今日は未完了です。"}</div>` : ""}
      </div>

      ${missedDetails ? `<div class="card"><strong>間違えた問題</strong>${missedDetails}</div>` : `<div class="success center">全問正解です。</div>`}

      <button class="btn btn-primary btn-wide" id="retry-round" type="button">同じ形式でもう一度</button>
      ${missed.length ? `<button class="btn btn-ghost btn-wide" id="mastery-missed" type="button">定着するまで復習（${missed.length}語）</button>` : ""}
      <button class="btn btn-ghost btn-wide" type="button" data-nav="home">デッキ一覧へ</button>
    </div>
  `);

  document.getElementById("retry-round").addEventListener("click", () => {
    const settings = loadSettings();
    const config = result.config.gameMode === "DAILY"
      ? createDailyRoundConfig(settings, loadDailyChallengeSettings())
      : result.config.gameMode === "MASTERY"
        ? createRoundConfig(result.config.deck, result.config.prefs, settings, {
            gameMode: "MASTERY",
            fixedItems: result.config.items,
            reverse: result.config.reverse
          })
        : createRoundConfig(result.config.deck, result.config.prefs, settings, {
            gameMode: result.config.gameMode,
            reverse: result.config.reverse
          });
    if (!config.items.length) {
      alert(config.gameMode === "THREE_CORRECT"
        ? "この設定では、すべてのカードが累積3回正解に到達しています。"
        : "この組み合わせでは問題を作れませんでした。");
      navigate("fields");
      return;
    }
    startQuiz(config);
  });

  document.getElementById("mastery-missed")?.addEventListener("click", () => {
    const settings = loadSettings();
    const config = result.config.gameMode === "DAILY"
      ? {
          ...result.config,
          items: missed,
          gameMode: "MASTERY",
          dailyKey: null,
          dailyGoalType: null,
          dailyTargetValue: 0,
          initialThreeCorrectCounts: Object.fromEntries(
            missed.map(item => [itemIdentity(item, result.config), result.config.initialThreeCorrectCounts?.[itemIdentity(item, result.config)] || 0])
          )
        }
      : createRoundConfig(result.config.deck, result.config.prefs, settings, {
          gameMode: "MASTERY",
          fixedItems: missed,
          reverse: result.config.reverse
        });
    startQuiz(config);
  });
}

function flashSpeechText(value, readParentheses) {
  let text = cleanText(value)
    .replace(/[↑↓]/g, " ")
    .replace(/[\\/]/g, "、");
  if (!readParentheses) {
    text = text.replace(/[（(][^）)]*[）)]/g, " ");
  }
  return text
    .replace(/\n+/g, "。")
    .replace(/\s+/g, " ")
    .trim();
}

function cancelFlashSpeech() {
  if ("speechSynthesis" in window) window.speechSynthesis.cancel();
}

function startFlash(items, settings) {
  state.flash?.stop();
  const speechRequested = Boolean(settings.flashSpeechEnabled);
  const speechSupported = "speechSynthesis" in window && "SpeechSynthesisUtterance" in window;
  state.flash = {
    items,
    index: 0,
    paused: false,
    secDeci: settings.flashcardDeci,
    gameFontSize: clampInt(settings.gameFontSize, 16, 96),
    speechRequested,
    speechEnabled: speechRequested && speechSupported,
    speechTarget: ["BOTH", "FRONT", "BACK"].includes(settings.flashSpeechTarget)
      ? settings.flashSpeechTarget
      : "BOTH",
    speechRate: Math.min(2, Math.max(0.5, Number(settings.flashSpeechRate) || 1)),
    frontWaitDeci: clampInt(settings.flashFrontWaitDeci, 0, 600),
    backWaitDeci: clampInt(settings.flashBackWaitDeci, 0, 600),
    repeatCount: clampInt(settings.flashRepeatCount, 1, 9),
    readParentheses: Boolean(settings.flashSpeechReadParentheses),
    showBothInitially: Boolean(settings.flashShowBothInitially),
    manualAdvanceWhenShowBoth: Boolean(settings.flashManualAdvanceWhenShowBoth),
    awaitingManualNext: false,
    phase: "FRONT",
    repeatIndex: 1,
    speaking: false,
    speechToken: 0,
    speechWatchdogId: null,
    timerId: null,
    remainingMs: settings.flashcardDeci * 100,
    lastTick: performance.now(),
    stop() {
      if (this.timerId != null) clearInterval(this.timerId);
      this.timerId = null;
      this.speechToken += 1;
      this.speaking = false;
      if (this.speechWatchdogId != null) clearTimeout(this.speechWatchdogId);
      this.speechWatchdogId = null;
      cancelFlashSpeech();
    }
  };
  navigate("flash");
}

function renderFlash() {
  const flash = state.flash;
  if (!flash) return navigate("home", { push: false });
  const speechSupported = "speechSynthesis" in window && "SpeechSynthesisUtterance" in window;
  app.innerHTML = `
    <main class="app-shell flash-shell" style="${gameFontStyle(flash.gameFontSize)}">
      <div class="quiz-header">
        <div>
          <div id="flash-count"></div>
          <div id="flash-phase" class="small subtle"></div>
        </div>
        <div class="row row-wrap font-controls">
          <button class="btn btn-ghost font-step" id="flash-font-smaller" type="button">A−</button>
          <span id="flash-font-label" class="small">${flash.gameFontSize}px</span>
          <button class="btn btn-ghost font-step" id="flash-font-larger" type="button">A＋</button>
          <button class="btn btn-ghost" id="finish-flash" type="button">終了</button>
        </div>
      </div>
      <div id="flash-progress"></div>
      ${flash.speechRequested && !flash.speechEnabled
        ? `<div class="error small">このブラウザーでは読み上げを利用できないため、通常の自動めくりで再生します。</div>`
        : ""}
      <section class="flash-content" id="flash-content"></section>
      <button class="btn btn-ghost btn-wide" id="flash-edit-card" type="button">カードを編集</button>
      <a class="btn btn-ghost btn-wide" id="flash-google-image-search" href="#" target="_blank" rel="noopener noreferrer">Google画像で検索</a>
      <button class="btn btn-ghost btn-wide" id="flash-repeat-speech" type="button" hidden>現在の面をもう一度読む</button>
      <div class="grid-3">
        <button class="btn btn-ghost" id="flash-prev" type="button">前へ</button>
        <button class="btn btn-primary" id="flash-pause" type="button">一時停止</button>
        <button class="btn btn-ghost" id="flash-next" type="button">次へ</button>
      </div>
    </main>`;
  const changeFlashFontSize = delta => {
    const next = clampInt(flash.gameFontSize + delta, 16, 96);
    if (next === flash.gameFontSize) return;
    flash.gameFontSize = next;
    const settings = loadSettings();
    saveSettings({ ...settings, gameFontSize: next });
    applyGameFontStyle(document.querySelector(".flash-shell"), next);
    document.getElementById("flash-font-label").textContent = `${next}px`;
    document.getElementById("flash-font-smaller").disabled = next <= 16;
    document.getElementById("flash-font-larger").disabled = next >= 96;
  };
  document.getElementById("flash-font-smaller").addEventListener("click", () => changeFlashFontSize(-4));
  document.getElementById("flash-font-larger").addEventListener("click", () => changeFlashFontSize(4));
  document.getElementById("flash-font-smaller").disabled = flash.gameFontSize <= 16;
  document.getElementById("flash-font-larger").disabled = flash.gameFontSize >= 96;
  document.getElementById("finish-flash").addEventListener("click", () => {
    flash.stop(); state.flash = null; navigate("fields");
  });
  document.getElementById("flash-prev").addEventListener("click", () => changeFlash(-1));
  document.getElementById("flash-next").addEventListener("click", () => changeFlash(1));
  document.getElementById("flash-pause").addEventListener("click", () => {
    flash.paused = !flash.paused;
    cancelFlashSpeech();
    flash.speaking = false;
    flash.speechToken += 1;
    flash.lastTick = performance.now();
    if (!flash.paused) beginFlashPhase({ preserveWait: true });
    updateFlashUi();
  });
  document.getElementById("flash-repeat-speech").addEventListener("click", () => {
    if (!flash.speechEnabled || !speechSupported) return;
    beginFlashPhase({ preserveWait: false });
  });
  document.getElementById("flash-edit-card").addEventListener("click", () => {
    const item = flash.items[flash.index];
    const wasPaused = flash.paused;
    flash.paused = true;
    cancelFlashSpeech();
    flash.speaking = false;
    flash.speechToken += 1;
    openCardEditor(item, {
      onSaved: () => renderFlashCardContent(),
      onClosed: () => {
        if (!state.flash || state.flash !== flash) return;
        flash.paused = wasPaused;
        flash.lastTick = performance.now();
        if (!flash.paused) beginFlashPhase({ preserveWait: true });
        updateFlashUi();
      }
    });
  });
  resetFlashCard();
  flash.timerId = setInterval(tickFlash, 100);
}

function resetFlashCard() {
  const flash = state.flash;
  if (!flash) return;
  flash.phase = "FRONT";
  flash.repeatIndex = 1;
  flash.awaitingManualNext = false;
  const manualAdvance = flash.showBothInitially && flash.manualAdvanceWhenShowBoth;
  flash.remainingMs = flash.speechEnabled ? flash.frontWaitDeci * 100 : flash.secDeci * 100;
  flash.lastTick = performance.now();
  renderFlashCardContent();
  if (!flash.speechEnabled && manualAdvance) {
    flash.awaitingManualNext = true;
    updateFlashUi();
    return;
  }
  beginFlashPhase({ preserveWait: true });
}

function renderFlashCardContent() {
  const flash = state.flash;
  if (!flash) return;
  const item = flash.items[flash.index];
  const revealBack = !flash.speechEnabled || flash.showBothInitially || flash.phase === "BACK";
  document.getElementById("flash-content").innerHTML = `
    <div class="stack">
      <div class="selectable flash-question">${textHtml(item.question)}</div>
      ${revealBack ? `<div class="selectable flash-answer">${textHtml(item.answer)}</div>` : ""}
    </div>`;
  const imageSearch = document.getElementById("flash-google-image-search");
  if (imageSearch) {
    const query = cleanText(item.question);
    imageSearch.href = query
      ? `https://www.google.com/search?tbm=isch&q=${encodeURIComponent(query)}`
      : "#";
    imageSearch.setAttribute("aria-disabled", query ? "false" : "true");
  }
  updateFlashUi();
}

function beginFlashPhase({ preserveWait = false } = {}) {
  const flash = state.flash;
  if (!flash || flash.paused || flash.awaitingManualNext) return;
  cancelFlashSpeech();
  if (flash.speechWatchdogId != null) clearTimeout(flash.speechWatchdogId);
  flash.speechWatchdogId = null;
  flash.speechToken += 1;
  const token = flash.speechToken;
  flash.speaking = false;
  flash.lastTick = performance.now();

  if (!flash.speechEnabled || !("speechSynthesis" in window) || !("SpeechSynthesisUtterance" in window)) {
    if (!preserveWait) flash.remainingMs = flash.secDeci * 100;
    updateFlashUi();
    return;
  }

  if (!preserveWait) {
    flash.remainingMs = (flash.phase === "FRONT" ? flash.frontWaitDeci : flash.backWaitDeci) * 100;
  }
  const item = flash.items[flash.index];
  const shouldSpeak = flash.phase === "FRONT"
    ? flash.speechTarget !== "BACK"
    : flash.speechTarget !== "FRONT";
  const raw = flash.phase === "FRONT" ? item.question : item.answer;
  const text = flashSpeechText(raw, flash.readParentheses);
  if (!shouldSpeak || !text) {
    if (flash.showBothInitially && flash.manualAdvanceWhenShowBoth &&
        flash.phase === "BACK" && flash.repeatIndex >= flash.repeatCount) {
      flash.awaitingManualNext = true;
      flash.remainingMs = 0;
    }
    updateFlashUi();
    return;
  }

  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = "ja-JP";
  utterance.rate = flash.speechRate;
  const finishSpeech = () => {
    if (!state.flash || state.flash !== flash || flash.speechToken !== token) return;
    if (flash.speechWatchdogId != null) clearTimeout(flash.speechWatchdogId);
    flash.speechWatchdogId = null;
    flash.speaking = false;
    flash.lastTick = performance.now();
    if (flash.showBothInitially && flash.manualAdvanceWhenShowBoth &&
        flash.phase === "BACK" && flash.repeatIndex >= flash.repeatCount) {
      flash.awaitingManualNext = true;
      flash.remainingMs = 0;
    }
    updateFlashUi();
  };
  utterance.onend = finishSpeech;
  utterance.onerror = finishSpeech;
  flash.speaking = true;
  const estimatedMs = Math.max(5000, Math.min(60000, Math.round(text.length * 420 / flash.speechRate + 3000)));
  flash.speechWatchdogId = setTimeout(finishSpeech, estimatedMs);
  window.speechSynthesis.speak(utterance);
  updateFlashUi();
}

function tickFlash() {
  const flash = state.flash;
  if (!flash || flash.paused || flash.awaitingManualNext || document.hidden) {
    if (flash) flash.lastTick = performance.now();
    return;
  }
  if (flash.speaking) {
    flash.lastTick = performance.now();
    return;
  }
  const now = performance.now();
  const delta = now - flash.lastTick;
  flash.lastTick = now;
  flash.remainingMs -= delta;
  if (flash.remainingMs <= 0) advanceFlashPhase();
  else updateFlashUi();
}

function advanceFlashPhase() {
  const flash = state.flash;
  if (!flash) return;
  if (!flash.speechEnabled || !("speechSynthesis" in window)) {
    changeFlash(1);
    return;
  }
  if (flash.phase === "FRONT") {
    flash.phase = "BACK";
    flash.remainingMs = flash.backWaitDeci * 100;
    renderFlashCardContent();
    beginFlashPhase({ preserveWait: true });
    return;
  }
  if (flash.repeatIndex < flash.repeatCount) {
    flash.repeatIndex += 1;
    flash.phase = "FRONT";
    flash.remainingMs = flash.frontWaitDeci * 100;
    renderFlashCardContent();
    beginFlashPhase({ preserveWait: true });
    return;
  }
  if (flash.showBothInitially && flash.manualAdvanceWhenShowBoth) {
    flash.awaitingManualNext = true;
    flash.remainingMs = 0;
    updateFlashUi();
    return;
  }
  changeFlash(1);
}

function changeFlash(delta) {
  const flash = state.flash;
  if (!flash) return;
  cancelFlashSpeech();
  if (flash.speechWatchdogId != null) clearTimeout(flash.speechWatchdogId);
  flash.speechWatchdogId = null;
  flash.speechToken += 1;
  flash.speaking = false;
  const next = flash.index + delta;
  if (next < 0) flash.index = 0;
  else if (next >= flash.items.length) {
    flash.stop(); state.flash = null; navigate("fields"); return;
  } else flash.index = next;
  resetFlashCard();
}

function updateFlashUi() {
  const flash = state.flash;
  if (!flash) return;
  document.getElementById("flash-count").textContent = `${flash.index + 1} / ${flash.items.length}`;
  const speechActive = flash.speechEnabled && "speechSynthesis" in window;
  document.getElementById("flash-phase").textContent = flash.awaitingManualNext
    ? "読み上げ完了・次へを押してください"
    : speechActive
      ? `${flash.phase === "FRONT" ? "表面" : "裏面"}・${flash.repeatIndex}/${flash.repeatCount}回${flash.speaking ? "・読み上げ中" : ""}`
      : "";
  const pauseButton = document.getElementById("flash-pause");
  pauseButton.textContent = flash.paused ? "再開" : "一時停止";
  pauseButton.disabled = flash.awaitingManualNext;
  const repeatButton = document.getElementById("flash-repeat-speech");
  if (repeatButton) {
    repeatButton.hidden = !speechActive;
    repeatButton.textContent = flash.phase === "FRONT" ? "表面をもう一度読む" : "裏面をもう一度読む";
  }
  const manualAdvance = flash.showBothInitially && flash.manualAdvanceWhenShowBoth;
  if (manualAdvance) {
    document.getElementById("flash-progress").innerHTML = flash.awaitingManualNext
      ? `<div class="subtle small">次へを押すまでこのカードを表示します。</div>`
      : "";
  } else {
    const total = speechActive
      ? Math.max(1, (flash.phase === "FRONT" ? flash.frontWaitDeci : flash.backWaitDeci) * 100)
      : Math.max(1, flash.secDeci * 100);
    const percent = Math.max(0, Math.min(100, (flash.remainingMs / total) * 100));
    document.getElementById("flash-progress").innerHTML = `<div class="progress"><div style="width:${percent}%"></div></div>`;
  }
}
function renderSettings() {
  const settings = loadSettings();
  app.innerHTML = shell("設定", `
    <div class="stack">
      <div class="card stack">
        <div class="grid-2">
          <label><span>既定の出題数（-1ですべて）</span><input class="field" id="s-count" type="number" min="-1" max="9999" value="${settings.count}"></label>
          <label><span>既定の制限時間（秒）</span><input class="field" id="s-time" type="number" min="0" max="600" value="${settings.timeLimitSec}"></label>
          <label><span>最大挑戦回数</span><input class="field" id="s-attempts" type="number" min="1" max="99" value="${settings.maxAttempts}"></label>
          <label><span>正誤表示時間（秒）</span><input class="field" id="s-feedback" type="number" min="0.1" max="60" step="0.1" value="${settings.feedbackDeci / 10}"></label>
          <label><span>めくり表示時間（読み上げOFF時・秒）</span><input class="field" id="s-flash" type="number" min="0.3" max="60" step="0.1" value="${settings.flashcardDeci / 10}"></label>
          <label><span>ゲーム画面の文字サイズ（16〜96px）</span><input class="field" id="s-font-size" type="number" min="16" max="96" value="${settings.gameFontSize}"></label>
        </div>
        <label class="row"><input id="s-flash-speech-enabled" type="checkbox" ${settings.flashSpeechEnabled ? "checked" : ""}><span>めくりモードで自動読み上げ</span></label>
        <div class="card stack" id="flash-speech-settings" ${settings.flashSpeechEnabled ? "" : "hidden"}>
          <strong>めくり読み上げ</strong>
          <label><span>読み上げる面</span><select class="field" id="s-flash-speech-target">
            <option value="BOTH" ${settings.flashSpeechTarget === "BOTH" ? "selected" : ""}>両方</option>
            <option value="FRONT" ${settings.flashSpeechTarget === "FRONT" ? "selected" : ""}>表面だけ</option>
            <option value="BACK" ${settings.flashSpeechTarget === "BACK" ? "selected" : ""}>裏面だけ</option>
          </select></label>
          <div class="grid-2">
            <label><span>読み上げ速度（0.5〜2.0倍）</span><input class="field" id="s-flash-speech-rate" type="number" min="0.5" max="2" step="0.05" value="${settings.flashSpeechRate}"></label>
            <label><span>同じカードの繰り返し</span><input class="field" id="s-flash-repeat" type="number" min="1" max="9" value="${settings.flashRepeatCount}"></label>
            <label><span>表面読み上げ後の待ち時間（秒）</span><input class="field" id="s-flash-front-wait" type="number" min="0" max="60" step="0.1" value="${settings.flashFrontWaitDeci / 10}"></label>
            <label><span>裏面読み上げ後の待ち時間（秒）</span><input class="field" id="s-flash-back-wait" type="number" min="0" max="60" step="0.1" value="${settings.flashBackWaitDeci / 10}"></label>
          </div>
          <label class="row"><input id="s-flash-show-both" type="checkbox" ${settings.flashShowBothInitially ? "checked" : ""}><span>最初から問題と答えを両方表示</span></label>
          <div class="subtle small">オンでは、読み上げ中も答えを隠さず最初から表示します。</div>
          <label class="row"><input id="s-flash-manual-advance" type="checkbox" ${settings.flashManualAdvanceWhenShowBoth ? "checked" : ""}><span>表裏同時表示時は自動送りしない</span></label>
          <div class="subtle small">読み上げが終わっても停止し、「次へ」を押すまで同じカードを表示します。</div>
          <label class="row"><input id="s-flash-read-parentheses" type="checkbox" ${settings.flashSpeechReadParentheses ? "checked" : ""}><span>括弧内の注釈も読む</span></label>
          <div class="subtle small">↑・↓・HTMLタグは読み上げ時だけ除外します。</div>
        </div>
        <label class="row"><input id="s-auto" type="checkbox" ${settings.autoAdvance ? "checked" : ""}><span>正誤表示後に自動で次へ</span></label>
        <label class="row"><input id="s-new-only" type="checkbox" ${settings.newOnly ? "checked" : ""}><span>新規カードだけ出題</span></label>
        <label class="row"><input id="s-weak" type="checkbox" ${settings.weakPriority ? "checked" : ""}><span>苦手カードを優先</span></label>
        <label class="row"><input id="s-selection-pause" type="checkbox" ${settings.pauseWhileSelecting ? "checked" : ""}><span>文字選択中はタイマーを停止</span></label>
        <label class="row"><input id="s-shared-three-all" type="checkbox" ${settings.sharedThreeCorrectAllModes ? "checked" : ""}><span>成功回数を全モードで共通にする（3回で全モードから除外）</span></label>
        <button class="btn btn-primary btn-wide" id="save-settings" type="button">保存</button>
        <div id="settings-message"></div>
      </div>

      <div class="card stack">
        <strong>成功回数</strong>
        <div class="subtle small">共通設定がオンならデッキとカードごと、オフなら3回正解モードのフィールド・方向ごとに保存されます。</div>
        <button class="btn btn-danger btn-wide" id="reset-three-all" type="button">すべての成功回数をリセット</button>
      </div>

      <div class="card stack">
        <strong>バックアップ</strong>
        <div class="subtle small">デッキ、設定、履歴、苦手度を1つのJSONに保存します。</div>
        <button class="btn btn-ghost btn-wide" id="export-backup" type="button">Web版の全データを保存</button>
      </div>

      <button class="btn btn-ghost btn-wide" type="button" data-nav="home">戻る</button>
      <div class="footer-note">KanjiQuiz Web v${APP_VERSION}</div>
    </div>
  `);

  document.getElementById("s-flash-speech-enabled").addEventListener("change", event => {
    document.getElementById("flash-speech-settings").hidden = !event.target.checked;
  });

  document.getElementById("save-settings").addEventListener("click", () => {
    const next = {
      ...settings,
      count: clampInt(document.getElementById("s-count").value, -1, 9999),
      timeLimitSec: clampInt(document.getElementById("s-time").value, 0, 600),
      maxAttempts: clampInt(document.getElementById("s-attempts").value, 1, 99),
      feedbackDeci: clampInt(Math.round(Number(document.getElementById("s-feedback").value) * 10), 1, 600),
      flashcardDeci: clampInt(Math.round(Number(document.getElementById("s-flash").value) * 10), 3, 600),
      flashSpeechEnabled: document.getElementById("s-flash-speech-enabled").checked,
      flashSpeechTarget: document.getElementById("s-flash-speech-target").value,
      flashSpeechRate: Math.min(2, Math.max(0.5, Number(document.getElementById("s-flash-speech-rate").value) || 1)),
      flashFrontWaitDeci: clampInt(Math.round(Number(document.getElementById("s-flash-front-wait").value) * 10), 0, 600),
      flashBackWaitDeci: clampInt(Math.round(Number(document.getElementById("s-flash-back-wait").value) * 10), 0, 600),
      flashRepeatCount: clampInt(document.getElementById("s-flash-repeat").value, 1, 9),
      flashSpeechReadParentheses: document.getElementById("s-flash-read-parentheses").checked,
      flashShowBothInitially: document.getElementById("s-flash-show-both").checked,
      flashManualAdvanceWhenShowBoth: document.getElementById("s-flash-manual-advance").checked,
      gameFontSize: clampInt(document.getElementById("s-font-size").value, 16, 96),
      autoAdvance: document.getElementById("s-auto").checked,
      newOnly: document.getElementById("s-new-only").checked,
      weakPriority: document.getElementById("s-weak").checked,
      pauseWhileSelecting: document.getElementById("s-selection-pause").checked,
      sharedThreeCorrectAllModes: document.getElementById("s-shared-three-all").checked
    };
    saveSettings(next);
    document.getElementById("settings-message").innerHTML = `<div class="success">保存しました。</div>`;
  });

  document.getElementById("reset-three-all").addEventListener("click", () => {
    if (!confirm("全モード共通と3回正解モードの、すべての成功回数を削除しますか？")) return;
    resetAllThreeCorrectProgress();
    document.getElementById("settings-message").innerHTML = `<div class="success">すべての成功回数をリセットしました。</div>`;
  });

  document.getElementById("export-backup").addEventListener("click", async () => {
    const decks = await dbGetAllDecks();
    downloadJson(`KanjiQuiz-Web-backup-${todayKey()}.json`, {
      format: "KanjiQuizWebBackup",
      version: 1,
      exportedAt: new Date().toISOString(),
      decks,
      appData: {
        settings: loadSettings(),
        history: loadHistory(),
        cardStats: loadCardStats(),
        seenCards: loadSeenCards(),
        streak: readJson("kq.streak", { date: "", count: 0 }),
        dailyCompleted: readJson("kq.dailyCompleted", {}),
        dailyChallengeSettings: loadDailyChallengeSettings(),
        threeCorrectProgress: loadThreeCorrectProgress(),
        cardEdits: loadCardEdits()
      }
    });
  });
}


function renderCardProgress() {
  const deck = state.currentDeck;
  if (!deck) {
    navigate("home", { push: false });
    return;
  }
  const prefs = state.fieldConfig || loadFieldPrefs(deck);
  const settings = loadSettings();
  const profileKey = settings.sharedThreeCorrectAllModes
    ? sharedThreeCorrectProfileKey(deck)
    : threeCorrectProfileKey(deck, prefs, settings.reverse);
  const progress = getThreeCorrectProfile(profileKey);
  const items = uniqueBy(makeUsableItems(deck, prefs, settings.reverse), item => item.noteId)
    .sort((a, b) => {
      const countDiff = (progress[String(b.noteId)] || 0) - (progress[String(a.noteId)] || 0);
      return countDiff || a.question.localeCompare(b.question, "ja");
    });
  const completed = items.filter(item => (progress[String(item.noteId)] || 0) >= THREE_CORRECT_TARGET).length;

  const rows = items.map(item => {
    const count = progress[String(item.noteId)] || 0;
    return `
      <article class="card progress-card" data-progress-card
          data-search="${escapeHtml(`${item.question} ${item.displayAnswer}`.toLowerCase())}">
        <div class="stack compact">
          <div class="selectable"><strong>${textHtml(item.question)}</strong></div>
          ${item.displayAnswer ? `<div class="selectable subtle small">${textHtml(item.displayAnswer)}</div>` : ""}
          <div class="row-wrap space-between">
            <span class="${count >= THREE_CORRECT_TARGET ? "correct" : ""}"><strong>${count}/${THREE_CORRECT_TARGET}${count >= THREE_CORRECT_TARGET ? "　達成" : ""}</strong></span>
            <button class="btn btn-ghost" type="button" data-reset-progress="${escapeHtml(String(item.noteId))}" ${count > 0 ? "" : "disabled"}>リセット</button>
          </div>
        </div>
      </article>`;
  }).join("");

  app.innerHTML = shell("カード別の成功回数", `
    <div class="stack">
      <div class="card stack">
        <strong>${escapeHtml(deck.name)}</strong>
        <div class="subtle small">${settings.sharedThreeCorrectAllModes
          ? `全モード共通・達成 ${completed}/${items.length}`
          : `3回正解モードの現在設定・達成 ${completed}/${items.length}`}</div>
        <input class="field" id="progress-search" type="search" placeholder="カードを検索">
      </div>
      <div id="progress-empty" class="notice" ${items.length ? "hidden" : ""}>表示できるカードがありません。</div>
      <div class="stack" id="progress-list">${rows}</div>
      <button class="btn btn-ghost btn-wide" type="button" data-nav="fields">もどる</button>
    </div>
  `);

  const search = document.getElementById("progress-search");
  const empty = document.getElementById("progress-empty");
  search.addEventListener("input", () => {
    const query = search.value.trim().toLowerCase();
    let visible = 0;
    document.querySelectorAll("[data-progress-card]").forEach(card => {
      const show = !query || card.dataset.search.includes(query);
      card.hidden = !show;
      if (show) visible += 1;
    });
    empty.hidden = visible > 0;
    if (!visible) empty.textContent = "該当するカードがありません。";
  });

  document.querySelectorAll("[data-reset-progress]").forEach(button => {
    button.addEventListener("click", () => {
      const noteId = button.dataset.resetProgress;
      setThreeCorrectCount(profileKey, noteId, 0);
      renderCardProgress();
    });
  });
}

function renderHistory() {
  const entries = loadHistory();
  const deckOptions = [...new Set(entries.map(entry => entry.deck))].sort((a, b) => a.localeCompare(b, "ja"));
  app.innerHTML = shell("履歴", `
    <div class="stack">
      <div class="card grid-3">
        <label><span>デッキ</span><select class="select" id="history-deck"><option value="">すべて</option>${deckOptions.map(deck => `<option>${escapeHtml(deck)}</option>`).join("")}</select></label>
        <label><span>ゲーム形式</span><select class="select" id="history-mode"><option value="">すべて</option>${Object.entries(GAME_MODES).map(([key, label]) => `<option value="${key}">${escapeHtml(label)}</option>`).join("")}</select></label>
        <label><span>出題方向</span><select class="select" id="history-reverse"><option value="">すべて</option><option value="false">入力</option><option value="true">逆4択</option></select></label>
      </div>
      <div class="card"><canvas class="chart" id="history-chart" width="720" height="220" aria-label="正答率の推移"></canvas></div>
      <div id="history-list" class="stack"></div>
      <button class="btn btn-danger btn-wide" id="clear-history" type="button" ${entries.length ? "" : "disabled"}>履歴を削除</button>
      <button class="btn btn-ghost btn-wide" type="button" data-nav="home">戻る</button>
    </div>
  `);

  const update = () => {
    const deck = document.getElementById("history-deck").value;
    const mode = document.getElementById("history-mode").value;
    const reverse = document.getElementById("history-reverse").value;
    const filtered = entries.filter(entry => (!deck || entry.deck === deck) &&
      (!mode || entry.gameMode === mode) &&
      (!reverse || String(Boolean(entry.reverse)) === reverse));
    const list = document.getElementById("history-list");
    list.innerHTML = filtered.length ? filtered.map(entry => {
      const rate = entry.total ? Math.round((entry.correct / entry.total) * 100) : 0;
      return `<article class="card stack">
        <div class="row-wrap space-between"><strong>${escapeHtml(entry.deck)}</strong><span>${rate}%</span></div>
        <div>${escapeHtml(GAME_MODES[entry.gameMode] || "通常")}・${entry.reverse ? "逆4択" : "入力"}</div>
        <div class="subtle small">${formatDateTime(entry.timeMillis)}・${entry.correct}/${entry.total}・SCORE ${entry.score}・最大${entry.maxCombo || 0}コンボ・${entry.durationSec || 0}秒</div>
      </article>`;
    }).join("") : `<div class="card center subtle">該当する履歴はありません。</div>`;
    drawAccuracyChart(filtered.slice(0, 20).reverse().map(entry => entry.total ? (entry.correct / entry.total) * 100 : 0));
  };
  ["history-deck", "history-mode", "history-reverse"].forEach(id => document.getElementById(id).addEventListener("change", update));
  document.getElementById("clear-history").addEventListener("click", () => {
    if (!confirm("履歴をすべて削除しますか？")) return;
    localStorage.removeItem("kq.history");
    renderHistory();
  });
  update();
}

function drawAccuracyChart(rates) {
  const canvas = document.getElementById("history-chart");
  if (!canvas) return;
  const ratio = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  canvas.width = Math.max(320, Math.floor(rect.width * ratio));
  canvas.height = Math.floor(220 * ratio);
  const ctx = canvas.getContext("2d");
  ctx.scale(ratio, ratio);
  const width = rect.width;
  const height = 220;
  ctx.clearRect(0, 0, width, height);
  ctx.font = "12px system-ui";
  ctx.fillStyle = "#a7b0bf";
  ctx.strokeStyle = "#475569";
  for (const value of [0, 50, 100]) {
    const y = 190 - (value / 100) * 160;
    ctx.beginPath(); ctx.moveTo(34, y); ctx.lineTo(width - 12, y); ctx.stroke();
    ctx.fillText(`${value}%`, 2, y + 4);
  }
  if (!rates.length) {
    ctx.fillText("履歴がありません", 42, 110);
    return;
  }
  ctx.strokeStyle = "#a78bfa";
  ctx.lineWidth = 3;
  ctx.beginPath();
  rates.forEach((rate, index) => {
    const x = rates.length === 1 ? width / 2 : 34 + (index / (rates.length - 1)) * (width - 52);
    const y = 190 - (rate / 100) * 160;
    if (index === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.stroke();
  ctx.fillStyle = "#c4b5fd";
  rates.forEach((rate, index) => {
    const x = rates.length === 1 ? width / 2 : 34 + (index / (rates.length - 1)) * (width - 52);
    const y = 190 - (rate / 100) * 160;
    ctx.beginPath(); ctx.arc(x, y, 4, 0, Math.PI * 2); ctx.fill();
  });
}

function formatDateTime(timeMillis) {
  return new Intl.DateTimeFormat("ja-JP", {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
  }).format(new Date(timeMillis));
}

function safeFilename(name) {
  return String(name).replace(/[\\/:*?"<>|]/g, "_").slice(0, 80) || "deck";
}


app.addEventListener("click", event => {
  const button = event.target.closest("[data-nav]");
  if (!button || !app.contains(button)) return;
  event.preventDefault();
  navigate(button.dataset.nav);
});

document.addEventListener("selectionchange", () => {
  const session = state.quiz;
  if (!session || state.screen !== "quiz" || !session.config.pauseWhileSelecting) return;
  const selection = window.getSelection();
  const selectable = selection?.rangeCount ? selection.anchorNode?.parentElement?.closest?.(".selectable") : null;
  const selected = Boolean(selection && !selection.isCollapsed && selectable && app.contains(selectable));
  session.selectionPaused = selected;
  const label = document.getElementById("selection-pause");
  if (label) label.textContent = selected ? "文字選択中：タイマー停止" : "";
});

window.addEventListener("popstate", event => {
  if (state.screen === "quiz" && state.quiz && !state.quiz.ended) {
    history.pushState({ screen: "quiz" }, "", location.href);
    showQuitQuizModal(state.quiz);
    return;
  }
  const screen = event.state?.screen || "home";
  state.screen = screen;
  render();
});

window.addEventListener("beforeunload", event => {
  if (state.screen === "quiz" && state.quiz && !state.quiz.ended) {
    event.preventDefault();
    event.returnValue = "";
  }
});

window.visualViewport?.addEventListener("resize", () => {
  if (state.screen === "quiz") refreshQuizKeyboardLayout();
});

window.addEventListener("resize", () => {
  if (state.screen === "quiz") refreshQuizKeyboardLayout();
  if (state.screen === "history") {
    const filters = ["history-deck", "history-mode", "history-reverse"];
    if (filters.every(id => document.getElementById(id))) document.getElementById("history-deck").dispatchEvent(new Event("change"));
  }
});

async function init() {
  try {
    await refreshDecks();
    history.replaceState({ screen: "home" }, "", location.href);
    if ("serviceWorker" in navigator && location.protocol.startsWith("http")) {
      navigator.serviceWorker.register("./sw.js").catch(() => {});
    }
    renderHome();
    bindGlobalNav();
  } catch (error) {
    app.innerHTML = shell("KanjiQuiz Web", `<div class="error">初期化に失敗しました：${escapeHtml(error.message)}</div>`);
  }
}

init();
