package dev.davidv.londontransport.santander

import android.content.Context

/** Replies carry account details, so a published build never writes them anywhere. */
@Suppress("UNUSED_PARAMETER")
fun replyRecorder(context: Context): ReplyRecorder = ReplyRecorder { _, _ -> }
