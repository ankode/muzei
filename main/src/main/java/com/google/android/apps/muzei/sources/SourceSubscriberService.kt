/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.sources

import android.app.IntentService
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_PUBLISH_STATE
import com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_STATE
import com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN
import com.google.android.apps.muzei.api.internal.SourceState
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.sync.TaskQueueService
import kotlinx.coroutines.experimental.runBlocking
import java.util.ArrayList

class SourceSubscriberService : IntentService("SourceSubscriberService") {

    companion object {
        private const val TAG = "SourceSubscriberService"
    }

    override fun onHandleIntent(intent: Intent?) {
        if (ACTION_PUBLISH_STATE != intent?.action) {
            return
        }
        // Handle API call from source
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val state = intent.getBundleExtra(EXTRA_STATE)?.run {
            SourceState.fromBundle(this)
        } ?: return // If there's no state, there's nothing to change

        runBlocking {
            update(token, state)
        }
    }

    private suspend fun update(token: String, state: SourceState)  {
        val database = MuzeiDatabase.getInstance(this)
        val source = database.sourceDao().getCurrentSource()
        if (source == null || token != source.componentName.flattenToShortString()) {
            Log.w(TAG, "Dropping update from non-selected source, token=$token " +
                    "does not match token for ${source?.componentName}")
            return
        }

        source.apply {
            description = state.description
            wantsNetworkAvailable = state.wantsNetworkAvailable
            supportsNextArtwork = false
            commands = ArrayList()
        }
        val numSourceActions = state.numUserCommands
        for (i in 0 until numSourceActions) {
            val command = state.getUserCommandAt(i)
            when (state.getUserCommandAt(i)?.id) {
                MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK -> source.supportsNextArtwork = true
                else -> source.commands.add(command)
            }
        }

        val currentArtwork = state.currentArtwork
        if (currentArtwork != null) {
            database.beginTransaction()
            database.sourceDao().update(source)
            val artwork = Artwork().apply {
                sourceComponentName = source.componentName
                imageUri = currentArtwork.imageUri
                title = currentArtwork.title
                byline = currentArtwork.byline
                attribution = currentArtwork.attribution
                this.token = currentArtwork.token
                if (currentArtwork.metaFont != null) {
                    metaFont = currentArtwork.metaFont
                }
                viewIntent = currentArtwork.viewIntent
            }

            if (artwork.viewIntent != null) {
                try {
                    // Make sure we can construct a PendingIntent for the Intent
                    PendingIntent.getActivity(this,0, artwork.viewIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT)
                } catch (e: RuntimeException) {
                    // This is actually meant to catch a FileUriExposedException, but you can't
                    // have catch statements for exceptions that don't exist at your minSdkVersion
                    Log.w(TAG, "Removing invalid View Intent that contains a file:// URI: ${artwork.viewIntent}", e)
                    artwork.viewIntent = null
                }
            }

            val artworkId = database.artworkDao().insert(artwork)

            database.setTransactionSuccessful()
            database.endTransaction()
            database.artworkDao().insertCompleted(this, artworkId)

            // Download the artwork contained from the newly published SourceState
            startService(TaskQueueService.getDownloadCurrentArtworkIntent(this))
        }
    }
}
