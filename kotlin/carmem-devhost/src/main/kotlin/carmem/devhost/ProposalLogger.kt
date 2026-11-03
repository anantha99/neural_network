package carmem.devhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Logs what ProposalBus publishes, standing in for the assistant UI / TTS layer. */
class ProposalLogger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(DevHostApp.TAG, "proposals: ${intent.getStringExtra("proposals")}")
    }
}
