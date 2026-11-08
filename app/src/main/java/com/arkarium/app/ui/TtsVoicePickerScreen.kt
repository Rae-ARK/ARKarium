package com.arkarium.app.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arkarium.app.playback.descriptionOf
import com.arkarium.app.playback.languagesOf
import com.arkarium.app.playback.rowsForLanguage
import com.arkarium.app.playback.toTtsVoiceInfo
import com.arkarium.app.playback.withFavoritesFirst
import java.util.Locale

// A full
// `settings/tts/voice` page, replacing TtsSettingsScreen's old 420dp
// AlertDialog (VoicePickerDialog/VoicePickerRow, now deleted). Reads the
// reference screenshots' two-step flow ("Language" line opens a language
// dialog, then one page of voices for that language) rather than one long
// scrollable list of every voice at once - matching a reference reader app's
// language-then-voice flow.
//
// Also answers that doc's "Should the voice page have a play a sample
// button?" open question: yes, and tapping a row both selects that voice
// *and* plays it, using the exact same sample line every time (SAMPLE_TEXT
// below) - the ordinary way a picker like this lets someone compare voices
// is to hear them say the same thing, not different things.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsVoicePickerScreen(
    availableVoices: List<Voice>,
    currentVoiceName: String?,
    // Rung 5: Voice.getName() values with a star. Empty for an install that
    // has never favorited anything - see PreferencesManager.ttsFavoriteVoiceNames.
    favoriteVoiceNames: Set<String>,
    // Pushed live to the attached service and persisted by the caller (same
    // split TtsSettingsScreen's old onVoiceChange used) - this screen only
    // ever reports *which* name was picked, never touches PreferencesManager
    // or TtsPlaybackService directly.
    onVoiceSelected: (String?) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val voiceInfos = remember(availableVoices) { availableVoices.map { it.toTtsVoiceInfo() } }
    val deviceLanguage = remember { Locale.getDefault().language }
    val languages = remember(voiceInfos, deviceLanguage) {
        languagesOf(voiceInfos, deviceLanguage)
    }
    var selectedLanguage by remember(languages) { mutableStateOf(languages.firstOrNull()) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    // Sample-playback engine, owned by this page alone - deliberately not the
    // shared TtsPlaybackService (ui/Tts.kt's ChapterTtsState/rememberChapterTts),
    // which is reserved for actual chapter playback (TtsPlaybackService's own
    // doc comment is explicit that it is the engine's sole owner, so nothing
    // races its init). Previewing a voice here would otherwise have to borrow that
    // engine mid-preview, stomping whatever voice/rate a real read-aloud
    // session already had live. This instance only ever speaks SAMPLE_TEXT,
    // never a chapter, and is shut down the moment this screen leaves
    // composition.
    var previewEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var previewReady by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context.applicationContext) { status ->
            previewReady = status == TextToSpeech.SUCCESS
        }
        previewEngine = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            previewEngine = null
        }
    }

    // Plays SAMPLE_TEXT on the preview engine using `voice` (null = the
    // engine's own default, same meaning setVoice(null) already has
    // everywhere else). A silent no-op before the preview engine's own init
    // callback has landed - nothing queues invisibly to surprise the reader
    // once it does.
    fun playSample(voice: Voice?) {
        val engine = previewEngine ?: return
        if (!previewReady) return
        if (voice != null) engine.setVoice(voice)
        engine.speak(SAMPLE_TEXT, TextToSpeech.QUEUE_FLUSH, null, "voice_sample")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Choose a voice") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        // Reference screenshots' "Language - English" line, above the voice
        // list rather than inside it - tapping it opens the language dialog
        // below instead of the list scrolling to a header in place.
        if (languages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLanguageDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    "Language",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    selectedLanguage?.let { Locale(it).displayLanguage.replaceFirstChar { c -> c.uppercase() } }
                        ?: "-",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        }

        // Computed here, in the composable function body, rather than inside
        // the LazyColumn's content lambda below: that lambda is a
        // LazyListScope builder, not a @Composable context, so the `remember`
        // call that used to live in there (guarded by `if (language != null)`)
        // failed to compile - "remember" is itself @Composable and can't be
        // invoked from LazyListScope directly, only from inside an `item`/
        // `items` row lambda. Hoisting it here and falling back to an empty
        // list when no language is selected keeps the exact same rendered
        // result (the `items` call below renders nothing for an empty list,
        // same as the old `if` skipping it) without calling remember from a
        // non-composable scope.
        val language = selectedLanguage
        val rows = remember(voiceInfos, language, favoriteVoiceNames) {
            if (language != null) {
                withFavoritesFirst(rowsForLanguage(voiceInfos, language), favoriteVoiceNames)
            } else {
                emptyList()
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Kept as the list's first row rather than folded into the
            // language filter (it has no language of its own).
            item {
                TtsVoiceRow(
                    title = "System default",
                    subtitle = null,
                    selected = currentVoiceName == null,
                    online = false,
                    isFavorite = null,
                    onToggleFavorite = {},
                    onClick = {
                        onVoiceSelected(null)
                        playSample(null)
                    }
                )
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }

            items(rows, key = { it.name }) { info ->
                val voice = availableVoices.firstOrNull { it.name == info.name }
                TtsVoiceRow(
                    title = info.name,
                    subtitle = descriptionOf(info).replaceFirstChar { it.uppercase() },
                    selected = info.name == currentVoiceName,
                    online = info.online,
                    isFavorite = info.name in favoriteVoiceNames,
                    onToggleFavorite = { onToggleFavorite(info.name) },
                    onClick = {
                        onVoiceSelected(info.name)
                        playSample(voice)
                    }
                )
            }
        }
    }

    if (showLanguageDialog) {
        LanguageDialog(
            languages = languages,
            selectedLanguage = selectedLanguage,
            onLanguageSelected = { language ->
                selectedLanguage = language
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

// The device's languages first (already `languages`' own order, from
// languagesOf), each shown by its display name - the reference screenshots'
// "Language dialog" step. ARKarium only ever lists languages a voice is
// actually available for - a reference app's "Specify language" (every
// language the engine supports, voice or not) isn't copied: that list
// would have empty pages here.
@Composable
private fun LanguageDialog(
    languages: List<String>,
    selectedLanguage: String?,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Language") },
        // Plain Column with no scroll modifier used to just keep growing
        // past the dialog's own bounds on a device with many TTS languages
        // installed - AlertDialog doesn't clip or scroll its `text` content
        // for you, so anything past the visible dialog height was simply
        // unreachable, with no indication more existed. heightIn(max = ...)
        // caps the dialog at a sane height instead of letting it grow to fit
        // every language (which would itself overflow the screen on a
        // long-enough list), and verticalScroll makes everything past that
        // cap reachable rather than just invisible. 360.dp is enough to show
        // 6-7 rows before scrolling kicks in, comfortable on a typical phone
        // screen alongside the dialog's title/button chrome.
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                languages.forEach { language ->
                    val label = Locale(language).displayLanguage.replaceFirstChar { it.uppercase() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(language) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = language == selectedLanguage, onClick = { onLanguageSelected(language) })
                        Text(label, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// One row of the voice page: name, description, an online badge (only drawn
// when `online` is true - the offline case is already stated in `subtitle`
// via descriptionOf, so it doesn't also need an icon), a favorite star (null
// `isFavorite` hides the star entirely - the "System default" row has no
// name of its own to favorite), and a trailing radio button for the current
// selection. Rung 4 (U1): the online badge is Icons.Filled.Cloud, not the
// old dialog's CloudOff - that glyph used to read as "offline" on an online
// voice, the exact wrong-way-round U1 flagged.
@Composable
private fun TtsVoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    online: Boolean,
    isFavorite: Boolean?,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    }
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    if (online) {
                        Icon(
                            Icons.Filled.Cloud,
                            contentDescription = "Requires a network connection",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(14.dp)
                        )
                    }
                }
            }
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

// The same line read aloud for every voice, regardless of language or
// quality - the point of a sample is comparing voices against each other,
// which only works if they're all saying the same thing. A pangram (every
// letter at least once) rather than book-like prose, so the preview is
// short and still shows off a voice's range of sounds.
private const val SAMPLE_TEXT = "The quick brown fox jumps over the lazy dog."
