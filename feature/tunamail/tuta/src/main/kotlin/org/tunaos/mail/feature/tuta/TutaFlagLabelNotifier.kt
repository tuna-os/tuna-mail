package org.tunaos.mail.feature.tuta

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.k9mail.core.android.common.provider.NotificationIconResourceProvider
import net.thunderbird.feature.account.AccountId

/**
 * Tells the user that a Tuta account keeps flags on this device because it cannot have the `Flagged` label, with a
 * tap that opens [TutaFlagLabelActivity] to pick an existing label for flags instead.
 */
class TutaFlagLabelNotifier(
    private val context: Context,
    private val iconProvider: NotificationIconResourceProvider,
) {
    fun show(accountId: AccountId, accountName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.tuta_notices_channel_name))
                .build(),
        )

        val text = context.getString(R.string.tuta_flag_label_notice_text, accountName)
        val pendingIntent = PendingIntent.getActivity(
            context,
            accountId.toString().hashCode(),
            TutaFlagLabelActivity.intent(context, accountId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconProvider.pushNotificationIcon)
            .setContentTitle(context.getString(R.string.tuta_flag_label_notice_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        notificationManager.notify(accountId.toString(), NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "tuta_notices"

        /** With the account id as its tag, so each account has its own notice. */
        const val NOTIFICATION_ID = 0x7475
    }
}
