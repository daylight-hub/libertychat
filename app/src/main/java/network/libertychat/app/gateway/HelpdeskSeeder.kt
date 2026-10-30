package network.libertychat.app.gateway

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import network.libertychat.app.data.repository.ContactRepository
import network.libertychat.app.data.repository.ConversationRepository
import network.libertychat.app.data.repository.IdentityRepository
import network.libertychat.app.data.repository.Message
import javax.inject.Inject
import javax.inject.Singleton

/**
 * every identity starts with "LCS Helpdesk" saved as a contact and a welcome message
 * from it at the top of the conversation list, so help is one tap away.
 *
 * Runs once per identity (remembered in prefs): deleting the contact or the conversation
 * afterwards sticks. The welcome is stored locally; nothing is sent over the network.
 */
@Singleton
class HelpdeskSeeder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val identityRepository: IdentityRepository,
        private val contactRepository: ContactRepository,
        private val conversationRepository: ConversationRepository,
    ) {
        companion object {
            private const val TAG = "HelpdeskSeeder"
            // v2: the helpdesk address changed, so every identity is seeded again once.
            private const val PREF_SEEDED = "helpdesk_seeded_identities_v2"
            const val HELPDESK_ADDRESS = "225b1cc4c4a1d9fdf2db6dea26c33f82"

            /** The address earlier releases saved by mistake. Removed where it is still unused. */
            private const val FORMER_HELPDESK_ADDRESS = "199f2de1b645dc0289fbe7058cbe2a50"
            const val HELPDESK_NAME = "LCS Helpdesk"
            private val WELCOME =
                "Welcome to Liberty Chat! This is the LCS Helpdesk. Message us here any time with " +
                    "questions about the app, ${GatewayConfig.PRODUCT_NAME} or your subscription, and we'll " +
                    "reply as soon as we can."
        }

        private val prefs = context.getSharedPreferences(GatewayConfig.PREFS, Context.MODE_PRIVATE)

        fun start(appScope: CoroutineScope) {
            appScope.launch {
                identityRepository.activeIdentity
                    .map { it?.identityHash }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { hash -> runCatching { seed(hash) }.onFailure { Log.w(TAG, "Helpdesk seed failed", it) } }
            }
        }

        private suspend fun seed(identityHash: String) {
            val done = prefs.getStringSet(PREF_SEEDED, emptySet()).orEmpty()
            if (identityHash in done) return

            // Replace the wrong helpdesk address from earlier releases. Its conversation goes
            // too, unless the user wrote something in it.
            if (contactRepository.hasContact(FORMER_HELPDESK_ADDRESS)) {
                contactRepository.deleteContact(FORMER_HELPDESK_ADDRESS)
            }
            conversationRepository.getConversation(FORMER_HELPDESK_ADDRESS)?.let { old ->
                if (old.lastMessage == WELCOME) conversationRepository.deleteConversation(FORMER_HELPDESK_ADDRESS)
            }

            if (!contactRepository.hasContact(HELPDESK_ADDRESS)) {
                contactRepository.addPendingContact(HELPDESK_ADDRESS, HELPDESK_NAME).getOrThrow()
            }
            if (conversationRepository.getConversation(HELPDESK_ADDRESS) == null) {
                val now = System.currentTimeMillis()
                conversationRepository.saveMessage(
                    peerHash = HELPDESK_ADDRESS,
                    peerName = HELPDESK_NAME,
                    message =
                        Message(
                            id = "lcs-helpdesk-welcome-$identityHash",
                            destinationHash = HELPDESK_ADDRESS,
                            content = WELCOME,
                            timestamp = now,
                            isFromMe = false,
                            status = "delivered",
                            receivedAt = now,
                        ),
                )
            }
            prefs.edit().putStringSet(PREF_SEEDED, done + identityHash).apply()
            Log.i(TAG, "Added $HELPDESK_NAME for identity ${identityHash.take(8)}")
        }
    }
