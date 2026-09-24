package network.libertychat.app.rns.host

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import network.libertychat.app.rns.api.call.AcceptedCallLifecycle
import network.libertychat.app.rns.api.call.CallAttemptDirection
import network.libertychat.app.rns.api.call.CallAttemptRequest
import network.libertychat.app.rns.api.call.CallCallbackAdapter
import network.libertychat.app.rns.api.call.CallLifecycleRecorder
import network.libertychat.app.rns.api.call.DuplexModeController
import network.libertychat.app.rns.api.call.DuplexSignalling
import network.libertychat.app.rns.api.call.SerializedLifecycleCallbackAdapter
import network.libertychat.app.rns.api.model.NetworkStatus
import network.libertychat.app.rns.backend.py.ChaquopyRnsBackend
import network.libertychat.app.rns.backend.py.PyEventCallback
import network.libertychat.app.rns.backend.py.PyTwoArgCallback
import network.libertychat.app.rns.backend.py.PythonRnsRuntime
import network.libertychat.app.rns.host.persistence.CallsFromContactsGate
import network.libertychat.app.rns.host.persistence.ServiceSettingsAccessor
import tech.torlando.lxst.audio.Signalling
import tech.torlando.lxst.core.AudioDevice
import tech.torlando.lxst.core.AudioPacketHandler
import tech.torlando.lxst.core.CallController
import tech.torlando.lxst.core.CallCoordinator
import tech.torlando.lxst.core.CallState
import tech.torlando.lxst.core.PacketRouter
import tech.torlando.lxst.telephone.Profile
import tech.torlando.lxst.telephone.Telephone

/**
 * Python-flavor LXST telephony — sibling of `NativeCallManager`. The
 * audio stack (LXST-kt) is identical across backends; only the network
 * transport differs ([PythonNetworkTransport] routes through Python RNS
 * via Chaquopy). All call-state logic lives here in Kotlin per the
 * slim-Python rule (`:rns-backend-py/ARCHITECTURE.md`); `event_bridge.py`
 * carries only the per-callback bridge primitives.
 *
 * Setup auto-fires when the backend reaches READY — observer pattern
 * because `:rns-backend-py` can't call into `:rns-host` to do it inline.
 */
class PythonCallManager(
    private val context: Context,
    private val backend: ChaquopyRnsBackend,
    private val transport: PythonNetworkTransport,
    private val recorder: CallLifecycleRecorder,
    private val callCoordinator: CallCoordinator,
    private val settingsAccessor: ServiceSettingsAccessor,
    private val contactsGate: CallsFromContactsGate,
) : CallController {
    private val runtime: PythonRnsRuntime = backend.runtime
    private val backendStatusFlow: StateFlow<NetworkStatus> = backend.core.networkStatus
    private companion object {
        const val TAG = "PythonCallManager"

        /** LXST signalling field id — must match PythonNetworkTransport. */
        const val FIELD_SIGNALLING = 0x00
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packetRouter: PacketRouter = PacketRouter.getInstance(context)
    private val audioBridge: AudioDevice = AudioDevice.getInstance(context)

    @Volatile
    private var setupRan: Boolean = false

    /**
     * Shared serialized call-lifecycle owner: durable admission, at-most-one owned
     * attempt, and single-shot finalization for every accepted attempt.
     */
    private val acceptedCallLifecycle =
        AcceptedCallLifecycle(
            recorder = recorder,
            scope = scope,
            retainedAttempt = null,
            onDeferredShutdownCleanupComplete = { scope.cancel() },
        )

    /** Reduced inbound adapter: destination registration + link handshake + durable admission. */
    private val inboundCalls =
        PythonInboundCallAdapter(
            runtime = runtime,
            transport = transport,
            telephone = { telephone },
            owner = acceptedCallLifecycle,
            localIdentityHash = { localIdentityHash },
            scope = scope,
            onCallerIdentified = ::onCallerIdentified,
        )

    /** Symmetric callback adapter: routes LXST events into the shared serialized owner. */
    private val callbackAdapter: CallCallbackAdapter =
        SerializedLifecycleCallbackAdapter(acceptedCallLifecycle)

    /** Serialises [disableIncoming] / [enableIncoming] transitions. */
    private val incomingLock = Any()

    /**
     * LCS: duplex mode + transmit squelch for the active call.
     *
     * Owned here rather than in LXST-kt because this manager is the single
     * choke point for outbound audio — every encoded frame, from both the
     * Kotlin `Packetizer` and the native Oboe/C++ encode path, arrives at the
     * [AudioPacketHandler] installed in [setup]. Gating there means squelched
     * really is zero packets on the air, with no fork of LXST-kt required.
     */
    private val duplex = DuplexModeController()

    /**
     * LCS: this call was dialled half duplex; announce it when the callee rings.
     *
     * The preference cannot be sent at dial time — there is no link yet. LXST
     * sends it on inbound STATUS_RINGING, and consumes it there, so a redial
     * does not inherit a stale flag.
     */
    private val pendingHalfDuplex = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Hex hash of the local identity once the backend is READY (used for durable admission). */
    private val localIdentityHash: String
        get() = runtime.localIdentity?.let { ident ->
            (ident["hash"]
                ?.toJava(ByteArray::class.java)
                ?.joinToString("") { "%02x".format(it) })
                .orEmpty()
        }.orEmpty()

    init {
        // Auto-run setup() at backend READY (:rns-backend-py can't reach
        // here, so observe its status flow instead of being called inline
        // like NativeRnsBackendImpl.setupNativeTelephone). Also install
        // the profile-aware call hook so PythonRnsTelephony.initiateCall
        // can pass the codec profile through (default CallCoordinator
        // path drops it), and the setIncomingEnabled hook so the master
        // AIDL toggle reaches this manager from the UI process.
        scope.launch {
            backendStatusFlow.filter { it == NetworkStatus.READY }.first()
            if (!setupRan) {
                runCatching { setup() }.onFailure {
                    Log.e(TAG, "Auto-setup on backend READY failed", it)
                }
            }
            backend.telephonyImpl.profileAwareCallHook = { destHex, profileCode, halfDuplex ->
                call(destHex, profileCode, halfDuplex)
            }
            backend.telephonyImpl.profileSwitchHook = ::switchProfile
            backend.telephonyImpl.duplexModeHook = ::setDuplexMode
            backend.telephonyImpl.pttHook = ::setPttActive
            backend.telephonyImpl.setIncomingEnabledHook = ::setIncomingEnabled
        }

        // Duplex mode is per-call state, so it has to be cleared on every path
        // out of a call — including peer hangup and link loss, which never
        // reach this manager's own hangup(). Resetting in answer() instead
        // would be wrong: a caller dialling us already in half duplex sends
        // its mode preference while we are still ringing, and answering must
        // not discard it.
        scope.launch {
            callCoordinator.callState.collect { state ->
                when (state) {
                    is CallState.Idle, is CallState.Ended,
                    is CallState.Busy, is CallState.Rejected,
                    -> {
                        duplex.reset()
                        pendingHalfDuplex.set(false)
                        backend.telephonyImpl.publishActiveProfileCode(0)
                    }
                    else -> Unit
                }
            }
        }
    }

    /** The [Telephone], created in [setup]. */
    lateinit var telephone: Telephone
        private set

    /**
     * Wire the telephony stack and register the inbound-call destination.
     *
     * Call once after [PythonRnsRuntime.start] + `wireEventBridge` have
     * completed (the local identity and `RNS.Reticulum` are required).
     * Safe to call again after [shutdown].
     */
    fun setup() {
        if (setupRan) {
            Log.d(TAG, "setup() already ran — ignoring duplicate call")
            return
        }
        Log.i(TAG, "Setting up python-flavor telephony stack")

        val localIdentity = runtime.localIdentity
            ?: run {
                Log.e(TAG, "Cannot set up telephony: runtime.localIdentity is null (call after runtime.start)")
                return
            }
        setupRan = true

        transport.setLocalIdentity(localIdentity)
        packetRouter.setPacketHandler(
            object : AudioPacketHandler {
                override fun receiveAudioPacket(packet: ByteArray) {
                    // Half-duplex squelch. Dropping here rather than muting the
                    // mic is the whole point: a mute still transmits encoded
                    // silence at full frame rate, which costs the same airtime
                    // as speech on a 700-3200 bps link.
                    if (duplex.shouldTransmit()) transport.sendPacket(packet)
                }

                override fun receiveSignal(signal: Int) = transport.sendSignal(signal)
            },
        )
        transport.setPacketCallback { data -> packetRouter.onInboundPacket(data) }
        transport.inboundSignalTap = ::onInboundSignal
        telephone = Telephone(
            context = context,
            networkTransport = transport,
            audioBridge = audioBridge,
            networkPacketBridge = packetRouter,
            callBridge = callCoordinator,
        )
        callCoordinator.setCallManager(this)

        // Route LXST call-state callbacks into the shared serialized lifecycle.
        callCoordinator.setCallStateChangedListener { state, identityHash ->
            when (state) {
                "ringing" -> identityHash?.let(callbackAdapter::onRinging)
                "established" -> identityHash?.let(callbackAdapter::onEstablished)
                "busy" -> callbackAdapter.onBusy(identityHash)
                "rejected" -> callbackAdapter.onRejected(identityHash)
            }
        }
        callCoordinator.setCallEndedListener(callbackAdapter::onGenericEnded)

        inboundCalls.register(localIdentity)
        inboundCalls.announce()

        // Re-announce lxst.telephony whenever lxmf.delivery is (re-)announced
        // (periodic auto-announce + network-change announce, both routed
        // through PythonRnsCore.triggerAutoAnnounce). A one-time setup()
        // announce alone lets the telephony path go stale, so inbound callers
        // silently fail to reach us. announce() no-ops when the destination is
        // deregistered (incoming disabled), so the hook is safe to leave set.
        runtime.onLxmfReannounce = { inboundCalls.announce() }

        // Cold-start application of the persisted master toggle. If the
        // user turned voice calls OFF before the last :reticulum tear-down
        // (or before this fresh-start), apply that now — we register +
        // immediately deregister rather than skipping registration, so the
        // re-enable path uses the same single helper. Marginally wasteful
        // (~ms of work) but correct + minimal-conditional.
        if (!settingsAccessor.getAllowVoiceCalls()) {
            Log.i(TAG, "Cold-start: Allow voice calls = false, deregistering destination")
            inboundCalls.disable()
        }

        Log.i(TAG, "Python telephony stack ready")
    }

    /** Public so callers can couple lxst.telephony announces to LXMF reannounces. */
    fun announce(appData: ByteArray? = null) {
        inboundCalls.announce(appData)
    }

    // ===== Incoming call handling =====

    /**
     * Called when the incoming caller has sent their Reticulum identity.
     *
     * Applies the calls-from-contacts policy gate and the busy-line check, then
     * routes the accepted incoming attempt through the shared serialized owner's durable
     * admission. Admission failures (another owned attempt awaiting finalization, or a
     * durable insert failure) tear the link down without ringing or creating history.
     */
    private fun onCallerIdentified(link: PyObject, identity: PyObject) {
        val identityHash = identity["hash"]
            ?.toJava(ByteArray::class.java)
            ?.joinToString("") { "%02x".format(it) }
            .orEmpty()
        Log.i(TAG, "Caller identified: ${identityHash.take(16)}")

        // Calls-from-contacts-only gate. Fires BEFORE STATUS_RINGING and
        // BEFORE Telephone.onIncomingCall, so the originator only sees a
        // wait-time timeout (no STATUS_BUSY / STATUS_REJECTED) and this
        // device shows no UI / no ringtone. Same fail-open semantics as
        // ServicePersistenceManager.shouldBlockUnknownSender on the
        // message side.
        if (contactsGate.shouldSilentlyDrop(identityHash)) {
            Log.i(TAG, "Calls-only-from-contacts: dropping ${identityHash.take(16)}")
            runCatching { link.callAttr("teardown") }
            return
        }

        if (telephone.isCallActive()) {
            Log.w(TAG, "Line became busy after identify — signalling busy")
            sendSignalOnLink(link, Signalling.STATUS_BUSY)
            runCatching { link.callAttr("teardown") }
            return
        }

        // Durable admission + ringing persistence happen inside the owner BEFORE the
        // expose lambda accepts the link and rings. A rejection tears the link down.
        inboundCalls.expose(link, identityHash)
    }

    /**
     * Send a signal directly on a specific link, bypassing
     * [PythonNetworkTransport.sendSignal] which targets `activeLink`.
     * Used during the inbound handshake before the link is accepted.
     * Wire format must match the transport's sendSignal.
     */
    private fun sendSignalOnLink(link: PyObject, signal: Int) {
        runCatching {
            val packer = org.msgpack.core.MessagePack.newDefaultBufferPacker()
            packer.packMapHeader(1)
            packer.packInt(FIELD_SIGNALLING)
            packer.packArrayHeader(1)
            packer.packInt(signal)
            val pyData = runtime.python.builtins.callAttr("bytes", packer.toByteArray())
            runtime.rnsModule.callAttr("Packet", link, pyData).callAttr("send")
        }.onFailure { Log.w(TAG, "sendSignalOnLink($signal) failed: ${it.message}") }
    }

    // ===== CallController — invoked by CallCoordinator on UI actions =====

    override fun call(destinationHash: String) {
        call(destinationHash, null)
    }

    /**
     * LCS: change codec on an established call.
     *
     * Goes straight to [Telephone.switchProfile] rather than through
     * CallCoordinator, which holds no Telephone reference — the same reason
     * [profileAwareCallHook] exists for placing profile-aware calls.
     *
     * LXST reconfigures the transmit pipeline and signals the peer, whose own
     * LXST follows in `switchProfileFromRemote`. It already no-ops if the call
     * is not established or the profile is unchanged, so no guard is needed
     * here; an unknown code is the only real error and is worth surfacing.
     */
    fun switchProfile(profileCode: Int) {
        val profile =
            Profile.fromId(profileCode)
                ?: error("Unknown codec profile 0x${profileCode.toString(16)}")
        // LXST-kt's switchProfile() returns silently when the call is not
        // established, which used to surface as a success the UI acted on —
        // the local codec label changed while nothing went over the link.
        // Fail loudly instead so the ViewModel can report it.
        if (telephone.callStatus != Signalling.STATUS_ESTABLISHED) {
            error("Call not established; cannot switch codec (status=${telephone.callStatus})")
        }

        Log.i(TAG, "Switching call codec to ${profile.abbreviation}")
        // Use LXST-kt's built-in switchProfile: reconfigures our transmit AND
        // signals the peer (PREFERRED_PROFILE), exactly as Sideband/MeshChat do
        // when they switch. This is the mechanism dial-time already uses.
        telephone.switchProfile(profile)
        backend.telephonyImpl.publishActiveProfileCode(profile.id)
    }

    /**
     * LCS: switch the call between full and half duplex (LXST >= 0.5.0).
     *
     * Applies the mode locally (entering half duplex squelches immediately, so
     * the mic is dead until the first PTT press) and announces it to the peer
     * as `PREFERRED_MODE + mode`. An LXST >= 0.5.0 peer applies the same mode
     * to its own transmitter and does not echo the signal back; older peers
     * ignore it and stay full duplex, which is harmless.
     *
     * `isPttMode` is host-side call state, so updating it here drives the UI
     * through the existing observer for both local and peer-initiated switches.
     */
    fun setDuplexMode(halfDuplex: Boolean) {
        Log.i(TAG, "Switching call to ${if (halfDuplex) "half" else "full"} duplex")
        duplex.applyMode(halfDuplex)
        syncTransmitSquelch()
        callCoordinator.setPttModeLocally(halfDuplex)
        callCoordinator.setPttActiveLocally(false)
        transport.sendSignal(DuplexSignalling.signalFor(halfDuplex))
    }

    /** LCS: key (true) / unkey (false) the transmitter. No-op in full duplex. */
    fun setPttActive(active: Boolean) {
        duplex.setPttActive(active)
        syncTransmitSquelch()
    }

    /**
     * LCS battery saver: keep the native encoder skip in lockstep with the
     * squelch gate. When squelched (HDX, PTT released) the capture callback
     * skips the Opus/Codec2 encode entirely, so no codec work runs while
     * listening. shouldTransmit() is the single source of truth; !it is squelch.
     *
     * Requires LXST-kt >= 0.0.9-lcs (Telephone.setTransmitSquelch). Safe no-op
     * before a call is established — the flag is re-synced on every mode/PTT
     * change and on call setup.
     */
    private fun syncTransmitSquelch() {
        runCatching { telephone.setTransmitSquelch(!duplex.shouldTransmit()) }
            .onFailure { Log.w(TAG, "setTransmitSquelch failed", it) }
    }

    /**
     * LCS: peer-initiated duplex switch, tapped off the inbound signal stream.
     *
     * Not echoed back — matching LXST's `switch_mode(from_signalling=True)`.
     */
    private fun onInboundSignal(signal: Int) {
        // Caller side: the callee is ringing, so the link is up and the mode
        // preference can go out. LXST batches this with the profile preference
        // in one packet; two packets are equivalent, since the receiver
        // dispatches each signal in the array independently.
        if (signal == Signalling.STATUS_RINGING && pendingHalfDuplex.compareAndSet(true, false)) {
            Log.i(TAG, "Dialling half duplex; announcing mode to callee")
            setDuplexMode(true)
            return
        }

        // The effective profile can change without any local action: the caller
        // announces its choice while we are ringing, and either side can switch
        // mid-call. Publishing here is what lets the UI show the truth on the
        // receiving end of both — otherwise it shows whatever this device
        // last picked, which for a callee is simply the default.
        if (signal >= DuplexSignalling.PREFERRED_PROFILE) {
            val code = signal - DuplexSignalling.PREFERRED_PROFILE
            Log.i(TAG, "Peer profile signal: 0x${code.toString(16)}")
            backend.telephonyImpl.publishActiveProfileCode(code)
            return
        }

        if (!DuplexSignalling.isModeSignal(signal)) return
        val halfDuplex = DuplexSignalling.isHalfDuplexSignal(signal)
        Log.i(TAG, "Peer switched call to ${if (halfDuplex) "half" else "full"} duplex")
        duplex.applyMode(halfDuplex)
        syncTransmitSquelch()
        callCoordinator.setPttModeLocally(halfDuplex)
        callCoordinator.setPttActiveLocally(false)
    }

    /** Profile-aware overload — invoked from PythonRnsTelephony via the hook. */
    fun call(
        destinationHash: String,
        profileCode: Int?,
        halfDuplex: Boolean = false,
    ) {
        duplex.reset()
        pendingHalfDuplex.set(halfDuplex)
        backend.telephonyImpl.publishActiveProfileCode(profileCode ?: 0)
        scope.launch {
            val destBytes = destinationHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val profile = profileCode?.let { code ->
                Profile.fromId(code).also {
                    if (it == null) Log.w(TAG, "Unknown LXST profile code 0x${code.toString(16)}, defaulting")
                }
            } ?: Profile.DEFAULT
            // Durable admission happens BEFORE outbound signalling: the attempt row is
            // created and the lifecycle owner latches it before telephone.call() launches.
            // If admission fails, the call is not placed and no history is recorded.
            val request =
                CallAttemptRequest(
                    direction = CallAttemptDirection.OUTGOING,
                    localIdentityHash = localIdentityHash,
                    remoteIdentityHash = destinationHash,
                    codecProfileCode = profileCode,
                )
            val result =
                acceptedCallLifecycle.admitOutgoing(request) { _ ->
                    Log.i(TAG, "Calling with profile ${profile.abbreviation} (0x${profile.id.toString(16)})")
                    telephone.call(destBytes, profile)
                }
            if (result.isFailure) {
                Log.w(TAG, "Outgoing call not admitted: ${result.exceptionOrNull()}")
            }
        }
    }

    override fun answer() {
        telephone.answer()
    }

    override fun hangup() {
        duplex.reset()
        // Local decline (incoming pre-answer) / cancel (outgoing pre-connect) intent
        // is latched BEFORE the telephone hangs up; the owner decides the exact outcome
        // from the active attempt's direction. A connected call is unaffected here.
        when (acceptedCallLifecycle.activeAttempt?.direction) {
            CallAttemptDirection.INCOMING -> callbackAdapter.onLocalDecline()
            CallAttemptDirection.OUTGOING -> callbackAdapter.onLocalCancel()
            null -> Unit
        }
        telephone.hangup()
    }

    override fun muteMicrophone(muted: Boolean) {
        telephone.muteTransmit(muted)
    }

    override fun setSpeaker(enabled: Boolean) {
        audioBridge.setSpeakerphoneOn(enabled)
    }

    // ===== Master incoming-calls toggle =====

    /**
     * Apply [setIncomingEnabled] for this manager.
     *
     * Invoked by [network.libertychat.app.rns.backend.py.PythonRnsTelephony]'s
     * `setIncomingEnabledHook` (wired in [init]) when the UI calls
     * `RnsTelephony.setIncomingEnabled(...)` across the AIDL boundary.
     *
     * Idempotent — applying the same state twice is a no-op.
     */
    fun setIncomingEnabled(enabled: Boolean) {
        if (enabled) enableIncoming() else disableIncoming()
    }

    private fun disableIncoming() = synchronized(incomingLock) {
        inboundCalls.disable()
        if (::telephone.isInitialized && telephone.isCallActive()) {
            // Hang up active call so the remote sees a clean drop rather
            // than dead air. Hangup signal must travel BEFORE the
            // destination is gone.
            runCatching { telephone.hangup() }
                .onFailure { Log.w(TAG, "Ignored hangup error during disableIncoming", it) }
        }
    }

    private fun enableIncoming() = synchronized(incomingLock) {
        val ident = runtime.localIdentity
        if (ident == null) {
            // setup() hasn't run yet (cold-start before backend READY).
            // enable() will be a no-op until setup registers.
            Log.d(TAG, "enableIncoming: localIdentity not ready, will register at setup")
            return@synchronized
        }
        inboundCalls.enable(ident)
    }

    /** Tear down the telephony stack. Mirrors `NativeCallManager.shutdown()`. */
    fun shutdown() {
        Log.i(TAG, "Shutting down PythonCallManager")
        runtime.onLxmfReannounce = null
        if (::telephone.isInitialized && telephone.isCallActive()) {
            runCatching { telephone.hangup() }
                .onFailure { Log.w(TAG, "Ignored error hanging up on shutdown", it) }
        }
        callCoordinator.setCallManager(null)
        runCatching { transport.teardownLink() }
            .onFailure { Log.w(TAG, "Ignored error tearing down transport on shutdown", it) }
        inboundCalls.clear()
        scope.cancel()
    }
}
