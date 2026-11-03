package com.streamflixreborn.streamflix.downloads

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.streamflixreborn.streamflix.R

/** Menu and dialogs shared by the download button and the Downloads screen. */
object DownloadActions {

    private const val REQUEST_NOTIFICATIONS = 7_301

    private const val ID_PAUSE = 1
    private const val ID_RESUME = 2
    private const val ID_CANCEL = 3
    private const val ID_DELETE = 4
    private const val ID_RETRY = 5
    private const val ID_PLAY = 6

    fun activityOf(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /** Android 13+: the progress notification needs the runtime permission (downloads work without it). */
    fun requestNotificationPermission(activity: Activity?) {
        activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    /** Options for an existing download. [onPlay] adds a "Play" entry for completed downloads. */
    fun showMenu(anchor: View, item: DownloadTracker.Item, onPlay: (() -> Unit)? = null) {
        val context = anchor.context
        val popup = PopupMenu(context, anchor)
        val menu = popup.menu
        when (item.status) {
            DownloadTracker.Status.QUEUED,
            DownloadTracker.Status.WAITING,
            DownloadTracker.Status.DOWNLOADING -> {
                menu.add(Menu.NONE, ID_PAUSE, 0, R.string.download_menu_pause)
                menu.add(Menu.NONE, ID_CANCEL, 1, R.string.download_menu_cancel)
            }
            DownloadTracker.Status.PAUSED -> {
                menu.add(Menu.NONE, ID_RESUME, 0, R.string.download_menu_resume)
                menu.add(Menu.NONE, ID_CANCEL, 1, R.string.download_menu_cancel)
            }
            DownloadTracker.Status.COMPLETED -> {
                if (onPlay != null) menu.add(Menu.NONE, ID_PLAY, 0, R.string.download_menu_play)
                menu.add(Menu.NONE, ID_DELETE, 1, R.string.download_menu_delete)
            }
            DownloadTracker.Status.FAILED -> {
                menu.add(Menu.NONE, ID_RETRY, 0, R.string.download_menu_retry)
                menu.add(Menu.NONE, ID_CANCEL, 1, R.string.download_menu_cancel)
            }
            else -> return
        }
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                ID_PAUSE -> DownloadStarter.pause(context, item.id)
                ID_RESUME -> DownloadStarter.resume(context, item.id)
                ID_CANCEL -> DownloadStarter.remove(context, item.id)
                ID_DELETE -> confirmDelete(context, item)
                ID_RETRY -> DownloadStarter.retry(context, item)
                ID_PLAY -> onPlay?.invoke()
            }
            true
        }
        popup.show()
    }

    fun confirmDelete(context: Context, item: DownloadTracker.Item) {
        val title = item.metadata?.title ?: ""
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.download_delete_confirm, title))
            .setPositiveButton(R.string.download_delete) { _, _ -> DownloadStarter.remove(context, item.id) }
            .setNegativeButton(R.string.download_cancel, null)
            .show()
    }

    /** Short state text, e.g. "Downloading 42%". */
    fun statusText(context: Context, item: DownloadTracker.Item?): String = when (item?.status) {
        null, DownloadTracker.Status.NONE -> context.getString(R.string.download_action)
        DownloadTracker.Status.RESOLVING -> context.getString(R.string.download_state_resolving)
        DownloadTracker.Status.QUEUED -> context.getString(R.string.download_state_queued)
        DownloadTracker.Status.WAITING -> context.getString(
            if (item.waitingForWifi) R.string.download_state_waiting_wifi else R.string.download_state_waiting
        )
        DownloadTracker.Status.DOWNLOADING -> context.getString(R.string.download_state_downloading, item.percent.coerceAtLeast(0))
        DownloadTracker.Status.PAUSED -> context.getString(R.string.download_state_paused)
        DownloadTracker.Status.COMPLETED -> context.getString(R.string.download_state_completed)
        DownloadTracker.Status.FAILED -> context.getString(R.string.download_state_failed)
        DownloadTracker.Status.REMOVING -> context.getString(R.string.download_state_removing)
    }
}
