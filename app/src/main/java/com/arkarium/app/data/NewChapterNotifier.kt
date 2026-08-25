package com.arkarium.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.arkarium.app.MainActivity
import com.arkarium.app.R

// Posts the local notification NewChapterCheckWorker's background pass triggers when
// a synced novel with notifications enabled turns out to have new chapters (see
// docs/arkarium/NEW_CHAPTER_NOTIFICATIONS.md). Deliberately its own small object rather than
// folded into the worker itself - channel creation and PendingIntent-building are
// pure Android plumbing with no sync/DB logic in them, easy to keep separately
// readable (and, if it's ever needed, callable from somewhere other than the worker).
object NewChapterNotifier {
    // One channel for every "new chapter" notification, regardless of which novel it's
    // about - there's no per-novel channel because Android's channel list is a
    // system-level settings surface, not something a per-fiction toggle should be
    // multiplying. IMPORTANCE_DEFAULT (not HIGH) - this is "here's something to read
    // later," not time-critical, so it shouldn't heads-up/interrupt.
    private const val CHANNEL_ID = "new_chapters"

    // Distinguishes this extra from any other Intent extra MainActivity might read in
    // the future, and from a plain "novelId" string a future feature could reuse for
    // something unrelated.
    const val EXTRA_NOVEL_ID = "com.arkarium.app.EXTRA_NOTIFY_NOVEL_ID"

    private fun ensureChannel(context: Context) {
        // Channel creation is a one-time no-op on repeat calls (createNotificationChannel
        // is idempotent for an unchanged channel), so this is cheap enough to just call
        // unconditionally before every notify() rather than tracking "have I already
        // created this" separately - and it's a no-op entirely on API < 26, where
        // notification channels don't exist.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "New chapters",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Lets you know when a fiction you're following gets a new chapter"
        }
        manager.createNotificationChannel(channel)
    }

    // `newChapterCount` and `latestChapterTitle` come from NewChapterCheckWorker's
    // before/after chapter diff (see its doc comment) - latestChapterTitle is null
    // only if that diff somehow found new chapter rows with no resolvable title
    // (shouldn't happen in practice, ScannerImpl.parseChapter always produces one),
    // in which case the message falls back to a plain count.
    fun notify(context: Context, novel: NovelEntity, newChapterCount: Int, latestChapterTitle: String?) {
        // Checked here rather than relying on the system to silently drop the call -
        // NotificationManagerCompat.notify() on API 33+ without POST_NOTIFICATIONS
        // granted throws a SecurityException rather than no-opping, and a background
        // worker crashing (and retrying forever) over a permission the user simply
        // hasn't granted yet would be worse than just skipping this one novel's
        // notification silently. The toggle itself stays on for next time the
        // permission is granted - see MainActivity's notificationPermission launcher,
        // which is what prompts for it in the first place.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NOVEL_ID, novel.id)
        }
        // requestCode = novel.id.hashCode() so two different novels' notifications
        // (and their PendingIntents) never collide and clobber each other - a second
        // novel's notification arriving shouldn't silently replace/cancel a first
        // one's still-unread notification the way a shared requestCode of 0 would.
        val pendingIntent = PendingIntent.getActivity(
            context,
            novel.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = when {
            latestChapterTitle != null && newChapterCount == 1 -> "\"$latestChapterTitle\" is up"
            latestChapterTitle != null -> "\"$latestChapterTitle\" and ${newChapterCount - 1} more are up"
            newChapterCount == 1 -> "A new chapter is up"
            else -> "$newChapterCount new chapters are up"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(novel.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Same per-novel id as the PendingIntent's requestCode above, for the same
        // reason - each synced novel gets its own notification slot rather than every
        // fiction's "new chapter" alert overwriting whichever one last posted.
        NotificationManagerCompat.from(context).notify(novel.id.hashCode(), notification)
    }
}
