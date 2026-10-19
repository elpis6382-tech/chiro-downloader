package io.github.apostolichiro.videodl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifs {
    private const val CH_PROGRESS = "progress"
    private const val CH_DONE = "done"
    const val ID_PROGRESS = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "받는 중", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "다 받았을 때", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun viewIntent(f: SavedFile): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(f.uri), f.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(files: List<SavedFile>): Intent {
        val send = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).setType(files[0].mime).putExtra(Intent.EXTRA_STREAM, Uri.parse(files[0].uri))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { Uri.parse(it.uri) }))
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun progress(ctx: Context, title: String, text: String, percent: Int?, jobId: String?, cancelAll: Boolean = false): Notification {
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx))
            .setProgress(100, percent ?: 0, percent == null)
        if (jobId != null) {
            val cancel = PendingIntent.getService(
                ctx, 1, Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL).putExtra(DownloadService.EXTRA_ID, jobId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "취소", cancel)
        } else if (cancelAll) {
            val cancel = PendingIntent.getService(
                ctx, 2, Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "모두 취소", cancel)
        }
        return b.build()
    }

    fun finished(ctx: Context, job: Job) {
        val ok = job.status == Status.DONE
        val tap = if (ok && job.files.size == 1) {
            PendingIntent.getActivity(ctx, job.id.hashCode(), viewIntent(job.files[0]), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else openApp(ctx)
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (ok) "다 받았어요" else "못 받았어요")
            .setContentText(if (ok) Texts.title(job) else (job.error ?: Texts.title(job)))
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (ok) Texts.title(job) + "\n" + Texts.status(job) else Texts.title(job) + "\n" + (job.error ?: "")))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(job.id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}
