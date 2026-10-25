package com.example.cellularwanfailover.auth

import com.example.cellularwanfailover.power.BatterySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Progress of the pairing started from a QR code or link (one at a time). */
sealed interface PairingState {
    data object Idle : PairingState
    data class Waiting(val gatewayName: String, val ssid: String, val phoneId: String) : PairingState
    data class Paired(val gatewayName: String, val ssid: String, val phoneId: String) : PairingState
    data class Failed(val gatewayName: String, val reason: String) : PairingState
}

/** Authentication fields of an incoming request (headers) plus what they sign. */
data class SignedRequest(
    val gatewayId: String?,
    val phoneId: String?,
    val challenge: String?,
    val nonce: String?,
    val signature: String?,
    val method: String,
    val path: String,
    val body: ByteArray
)

/** A request verified as coming from a gateway; its answer must be signed with [signResponse]. */
class AuthContext internal constructor(
    val gatewayId: String,
    val phoneId: String,
    val gatewayName: String,
    internal val nonce: String,
    internal val signer: MessageSigner,
    internal val pending: Boolean
)

sealed interface AuthResult {
    data class Accepted(val context: AuthContext) : AuthResult
    data class Rejected(val reason: String) : AuthResult
}

@Serializable
data class ReplacedPairing(val phone_id: String, val sig: String)

@Serializable
data class PairCompleteResponse(val name: String, val ssid: String, val replaces: ReplacedPairing? = null)

@Serializable
data class DiscoveryEntry(
    val gw: String,
    val pid: String,
    /** "hot" while the phone offers an always-on tunnel, "cold" (on demand) otherwise */
    val standby: String,
    /** Battery state of charge (%) */
    val battery: Int,
    val plugged: Boolean,
    /** The phone's minimum battery (%), and whether it may still carry an outage below it when no other phone can */
    val min_battery: Int,
    val last_resort: Boolean,
    val sig: String
)

@Serializable
data class DiscoveryPayload(
    val service: String = AuthProtocol.SERVICE,
    val v: Int = AuthProtocol.VERSION,
    val pairings: List<DiscoveryEntry>
)

/**
 * Verifies gateway requests and signs the phone's answers (see PROTOCOL.md):
 *  - a gateway first fetches a single-use [issueChallenge], then signs its request with it;
 *  - the answer is signed for the gateway's nonce, so the gateway knows it is fresh and ours;
 *  - a pairing only counts on the Wi-Fi network (SSID) it was made on.
 * A new pairing stays pending until its gateway proves, by such a signed request over
 * that Wi-Fi, that it is on the network; only then is it saved (and the network trusted).
 */
class GatewayAuthenticator(
    private val repository: PairingRepository,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    companion object {
        const val CHALLENGE_TTL_MS = 30_000L
        // Per requester address, so flooding the phone only evicts the flooder's own challenges
        const val MAX_CHALLENGES_PER_CLIENT = 4
        // Memory bound across all requesters
        const val MAX_CHALLENGES = 256
    }

    private class IssuedChallenge(val expiry: Long, val client: String)

    private class PendingPairing(val invite: PairingInvite, val ssid: String) {
        val signer = memorySigner(invite.key)
    }

    private val json = Json { encodeDefaults = true }

    // challenge -> expiry and requester; insertion order lets the oldest be dropped first
    private val challenges = LinkedHashMap<String, IssuedChallenge>()

    private var pending: PendingPairing? = null

    // Answer to a completed pairing, replayed if the gateway retries (its first answer got lost)
    private var lastCompletion: Pair<String, PairCompleteResponse>? = null

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    val pairings: StateFlow<List<GatewayPairing>> get() = repository.pairings

    /** Emits whenever the set of networks the services should run on may have changed. */
    val trustChanges: Flow<Unit> = combine(repository.pairings, _pairingState) { _, _ -> }

    /** The services run on paired networks, and on the network of a pairing in progress. */
    @Synchronized
    fun isServiceSsid(ssid: String?): Boolean =
        ssid != null && (repository.isPairedSsid(ssid) || pending?.ssid == ssid)

    /** A single-use challenge for the requester at [client] (its IP address). */
    @Synchronized
    fun issueChallenge(client: String = ""): String {
        val now = clockMillis()
        challenges.entries.removeAll { it.value.expiry < now }
        // Drop the oldest of this requester's challenges first, never another requester's
        val own = challenges.entries.filter { it.value.client == client }.map { it.key }
        own.take((own.size - MAX_CHALLENGES_PER_CLIENT + 1).coerceAtLeast(0)).forEach { challenges.remove(it) }
        while (challenges.size >= MAX_CHALLENGES) {
            challenges.remove(challenges.keys.first())
        }
        return AuthProtocol.newId().also { challenges[it] = IssuedChallenge(now + CHALLENGE_TTL_MS, client) }
    }

    @Synchronized
    private fun consumeChallenge(challenge: String): Boolean {
        val issued = challenges.remove(challenge) ?: return false
        return issued.expiry >= clockMillis()
    }

    /**
     * Checks [request] for the phone currently on [currentSsid]. A pairing in progress is only
     * accepted where [allowPending] (the pairing completion itself).
     */
    @Synchronized
    fun authenticate(request: SignedRequest, currentSsid: String?, allowPending: Boolean = false): AuthResult {
        val gatewayId = request.gatewayId?.takeIf(AuthProtocol::isValidId)
        val phoneId = request.phoneId?.takeIf(AuthProtocol::isValidId)
        val challenge = request.challenge?.takeIf(AuthProtocol::isValidId)
        val nonce = request.nonce?.takeIf(AuthProtocol::isValidId)
        if (gatewayId == null || phoneId == null || challenge == null || nonce == null || request.signature.isNullOrEmpty()) {
            return AuthResult.Rejected("missing or malformed authentication headers")
        }
        if (!consumeChallenge(challenge)) return AuthResult.Rejected("unknown or expired challenge")

        val paired = repository.find(gatewayId, phoneId)
        val waiting = pending?.takeIf { it.invite.gatewayId == gatewayId && it.invite.phoneId == phoneId }
        val (signer, ssid, gatewayName) = when {
            paired != null -> Triple(repository.signer(paired), paired.ssid, paired.gatewayName)
            waiting != null && allowPending -> Triple(waiting.signer, waiting.ssid, waiting.invite.gatewayName)
            else -> return AuthResult.Rejected("unknown gateway or pairing")
        }
        if (signer == null) return AuthResult.Rejected("pairing secret missing")
        if (currentSsid != ssid) return AuthResult.Rejected("paired on another Wi-Fi network")

        val fields = AuthProtocol.requestFields(
            gatewayId, phoneId, request.method, request.path, challenge, nonce, request.body
        )
        if (!signer.verify(request.signature, fields)) return AuthResult.Rejected("bad signature")
        return AuthResult.Accepted(AuthContext(gatewayId, phoneId, gatewayName, nonce, signer, pending = paired == null))
    }

    fun signResponse(context: AuthContext, status: Int, body: ByteArray): String =
        context.signer.sign(AuthProtocol.responseFields(context.gatewayId, context.phoneId, context.nonce, status, body))

    @Synchronized
    fun beginPairing(invite: PairingInvite, ssid: String) {
        pending = PendingPairing(invite, ssid)
        _pairingState.value = PairingState.Waiting(invite.gatewayName, ssid, invite.phoneId)
    }

    /** Drops the pairing in progress (timeout, Wi-Fi change, user): nothing was saved. */
    @Synchronized
    fun cancelPairing(reason: String) {
        val current = pending ?: return
        pending = null
        _pairingState.value = PairingState.Failed(current.invite.gatewayName, reason)
    }

    /** Back to idle once the UI has shown the outcome. */
    @Synchronized
    fun acknowledgePairingResult() {
        if (pending == null) _pairingState.value = PairingState.Idle
    }

    /**
     * The gateway reached us over the pairing's Wi-Fi with a valid signature: save the pairing.
     * The answer tells the gateway our name and, if this replaces an earlier pairing with it,
     * proves (with the old secret) that the old entry is ours to retire. Null when there is no
     * such pairing (any more): the gateway must not save it.
     */
    @Synchronized
    fun completePairing(context: AuthContext, phoneName: String, nowMillis: Long = System.currentTimeMillis()): PairCompleteResponse? {
        val waiting = pending
        if (!context.pending || waiting == null || waiting.invite.phoneId != context.phoneId) {
            // Already completed: the gateway is retrying
            lastCompletion?.let { (phoneId, response) -> if (phoneId == context.phoneId) return response }
            val paired = repository.find(context.gatewayId, context.phoneId)
            // Otherwise the pairing was dropped since the request was checked (timeout, Wi-Fi change...)
            return paired?.let { PairCompleteResponse(name = phoneName, ssid = it.ssid) }
        }

        val invite = waiting.invite
        val replaces = repository.forGateway(invite.gatewayId)
            ?.takeIf { it.phoneId != invite.phoneId }
            ?.let { old ->
                repository.signer(old)?.let { signer ->
                    ReplacedPairing(old.phoneId, signer.sign(AuthProtocol.replaceFields(invite.gatewayId, old.phoneId, invite.phoneId)))
                }
            }
        val pairing = GatewayPairing(
            gatewayId = invite.gatewayId,
            gatewayName = invite.gatewayName,
            phoneId = invite.phoneId,
            ssid = waiting.ssid,
            pairedAt = nowMillis
        )
        repository.save(pairing, invite.key)
        pending = null
        val response = PairCompleteResponse(name = phoneName, ssid = waiting.ssid, replaces = replaces)
        lastCompletion = invite.phoneId to response
        _pairingState.value = PairingState.Paired(invite.gatewayName, waiting.ssid, invite.phoneId)
        return response
    }

    fun removePairing(gatewayId: String): GatewayPairing? = repository.remove(gatewayId)

    /** UDP discovery payload for the gateways paired on [currentSsid], or null if there are none. */
    @Synchronized
    fun discoveryPayload(
        currentSsid: String?,
        wifiIp: String?,
        standby: String = AuthProtocol.STANDBY_COLD,
        battery: Int = 100,
        plugged: Boolean = false,
        batterySettings: BatterySettings = BatterySettings()
    ): ByteArray? {
        if (currentSsid == null || wifiIp == null) return null
        val entries = mutableListOf<DiscoveryEntry>()
        fun add(gatewayId: String, phoneId: String, standby: String, signer: MessageSigner) {
            val (minBattery, lastResort) = batterySettings
            val fields = AuthProtocol.discoveryFields(gatewayId, phoneId, wifiIp, standby, battery, plugged, minBattery, lastResort)
            entries += DiscoveryEntry(gatewayId, phoneId, standby, battery, plugged, minBattery, lastResort, signer.sign(fields))
        }
        for (pairing in repository.pairings.value) {
            if (pairing.ssid != currentSsid) continue
            val signer = repository.signer(pairing) ?: continue
            add(pairing.gatewayId, pairing.phoneId, standby, signer)
        }
        // A pending pairing can't serve yet
        pending?.takeIf { it.ssid == currentSsid }?.let {
            add(it.invite.gatewayId, it.invite.phoneId, AuthProtocol.STANDBY_COLD, it.signer)
        }
        if (entries.isEmpty()) return null
        return json.encodeToString(DiscoveryPayload(pairings = entries)).toByteArray(Charsets.UTF_8)
    }
}
