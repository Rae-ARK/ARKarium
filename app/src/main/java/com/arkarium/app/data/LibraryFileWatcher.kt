package com.arkarium.app.data

import android.os.Build
import android.os.FileObserver
import java.io.File

// "Scan on file-change" (LibrarySettingsScreen's "Scanning behavior" section): a
// FileObserver-backed listener instead of polling, so dropping a file into the
// library folder triggers a rescan without waiting for the next launch or the next
// periodic LibraryScanWorker pass.
//
// Deliberately process-lifetime only, not a service: FileObserver's underlying
// inotify watches die the instant this process does, the same as any other in-memory
// object, so this can never notice a file dropped in while ARKarium is fully closed -
// that gap is exactly why "Background/periodic scan" (LibraryScanWorker) exists as a
// separate, WorkManager-backed opt-in for the "app isn't open" case. Started/stopped
// from MainActivity's onStart/onStop (an Activity-lifecycle pair, not onCreate/
// onDestroy) so the watch is live whenever ARKarium is in the foreground or visible,
// and torn down the moment it isn't - an inotify watch left running past that point
// would just be spending battery/file-descriptors watching a folder nothing is about
// to read from anyway.
//
// `onChanged` is debounced by the caller (see MainActivity's use of this class), not
// here: a single file drag-and-drop, or a sync writing a dozen chapter files in a row,
// fires this callback once per file, and collapsing that into one rescan is a
// timing/coroutine concern the caller already owns (a Job it can cancel-and-relaunch),
// not something a filesystem watcher itself should decide.
class LibraryFileWatcher(root: File, private val onChanged: () -> Unit) {

    // API 29+ has a File-based FileObserver constructor that watches the whole subtree
    // recursively on its own - exactly "a FileObserver/WatchService-style listener"
    // the proposal asks for, with no manual recursion needed. Below that, the only
    // constructor available is the deprecated String-path one, which watches just the
    // one directory's own immediate children; nested folders (a novel's own folder,
    // and any arc subfolders inside it) need their own individual watcher, enumerated
    // once at construction time. A folder created *after* construction on API < 29
    // (a brand-new novel's folder, or a brand-new arc folder inside an existing one)
    // won't get its own watcher until the next rescan re-creates this object - an
    // accepted gap on an 8-years-old-at-minSdk code path rather than reason to hold
    // the whole feature back from every other device.
    private val observers: List<FileObserver> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(recursiveObserver(root))
        } else {
            allDirectoriesUnder(root).map { legacyObserver(it) }
        }

    private fun recursiveObserver(dir: File): FileObserver =
        object : FileObserver(dir, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) = onChanged()
        }

    @Suppress("DEPRECATION")
    private fun legacyObserver(dir: File): FileObserver =
        object : FileObserver(dir.path, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) = onChanged()
        }

    private fun allDirectoriesUnder(root: File): List<File> =
        if (root.isDirectory) {
            listOf(root) + (root.listFiles()?.filter { it.isDirectory }?.flatMap { allDirectoriesUnder(it) } ?: emptyList())
        } else {
            emptyList()
        }

    fun start() = observers.forEach { it.startWatching() }

    fun stop() = observers.forEach { it.stopWatching() }

    companion object {
        // CREATE/DELETE/MOVED_* cover "a file or folder appeared/disappeared/got
        // renamed" (new chapter dropped in, a novel folder renamed); CLOSE_WRITE
        // covers "a file's contents changed and the writer is done with it" - MODIFY
        // alone fires on every buffered write, which would mean rescanning mid-copy.
        private const val WATCH_MASK = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.DELETE_SELF or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE
    }
}
