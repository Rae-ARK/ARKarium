package com.arkster.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkster.app.data.ChapterEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterEditorScreen(
    chapters: List<ChapterEntity>,
    initialArcStartIds: Set<String> = emptySet(),
    onSave: suspend (List<ChapterEntity>, Set<String>) -> Unit,
    onBack: () -> Unit
) {
    val editedChapters = remember { mutableStateOf(chapters) }
    val arcStartIds = remember { mutableStateOf(initialArcStartIds) }
    val coroutineScope = rememberCoroutineScope()

    fun updateTitle(chapter: ChapterEntity, newTitle: String) {
        editedChapters.value = editedChapters.value.map {
            if (it.id == chapter.id) it.copy(title = newTitle) else it
        }
    }

    fun toggleArcStart(chapter: ChapterEntity) {
        arcStartIds.value = if (chapter.id in arcStartIds.value) {
            arcStartIds.value - chapter.id
        } else {
            arcStartIds.value + chapter.id
        }
    }

    // Look up by id rather than by list index/object identity - updateTitle() replaces
    // every ChapterEntity with a new copy() on every keystroke, so a `chapter` reference
    // captured in a button's onClick can go stale and stop matching anything in
    // editedChapters.value via indexOf(), silently breaking reordering.
    fun moveUp(chapterId: String) {
        val currentIdx = editedChapters.value.indexOfFirst { it.id == chapterId }
        if (currentIdx > 0) {
            val updatedList = editedChapters.value.toMutableList()
            val temp = updatedList[currentIdx]
            updatedList[currentIdx] = updatedList[currentIdx - 1]
            updatedList[currentIdx - 1] = temp
            editedChapters.value = updatedList
        }
    }

    fun moveDown(chapterId: String) {
        val currentIdx = editedChapters.value.indexOfFirst { it.id == chapterId }
        if (currentIdx in 0 until editedChapters.value.size - 1) {
            val updatedList = editedChapters.value.toMutableList()
            val temp = updatedList[currentIdx]
            updatedList[currentIdx] = updatedList[currentIdx + 1]
            updatedList[currentIdx + 1] = temp
            editedChapters.value = updatedList
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Edit Chapters") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        // Without a weight, this LazyColumn was measured up to the full height the
        // parent Column had available and greedily claimed all of it (LazyColumn fills
        // available space in its scroll direction by default) - so the Save button
        // below, laid out after it in a plain non-scrolling Column, got pushed past the
        // bottom of the screen and was never reachable. weight(1f, fill = true) caps
        // the list to the space left over after the TopAppBar and Save button, matching
        // the same pattern NovelDetailScreen's chapter list already uses.
        LazyColumn(modifier = Modifier.weight(1f, fill = true)) {
            items(editedChapters.value, key = { it.id }) { chapter ->
                Card(modifier = Modifier.padding(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        TextField(
                            value = chapter.title,
                            onValueChange = { updateTitle(chapter, it) },
                            label = { Text("Title") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            IconButton(onClick = { moveUp(chapter.id) }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                            }

                            IconButton(onClick = { moveDown(chapter.id) }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                            }

                            FilterChip(
                                selected = chapter.id in arcStartIds.value,
                                onClick = { toggleArcStart(chapter) },
                                label = { Text("Arc start") },
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                coroutineScope.launch {
                    onSave(editedChapters.value, arcStartIds.value)
                    onBack()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("Save")
        }
    }
}
