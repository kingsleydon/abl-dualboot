package io.github.kingsleydon.abldualboot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** After boot, notices when a system update replaced the ROCKNIX ABL and offers a one-tap restore. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Root.available() && Device.ablStatus(context).replacedByUpdate) {
                    Notifications.show(
                        context, Notifications.Channel.BOOTLOADER,
                        "Linux boot menu was removed",
                        "A system update restored the stock bootloader. Tap to restore it.",
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
