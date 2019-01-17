package com.versatica.mediasoup

import com.alibaba.fastjson.JSON
import com.versatica.mediasoup.handlers.Handler
import com.versatica.mediasoup.handlers.sdp.*
import io.reactivex.Observable
import io.reactivex.ObservableOnSubscribe
import java.util.*
import kotlin.collections.ArrayList

val logger = Logger("Transport")

/**
 * Room class.
 *
 * @param {Object} [options]
 * @param {Object} [options.roomSettings] Remote room settings, including its RTP
 * capabilities, mandatory codecs, etc. If given, no "queryRoom" request is sent
 * to the server to discover them.
 * @param {Number} [options.requestTimeout=10000] - Timeout for sent requests
 * (in milliseconds). Defaults to 10000 (10 seconds).
 * @param {Object} [options.transportOptions] - Options for Transport created in mediasoup.
 * @param {Array<RTCIceServer>} [options.turnServers] - Array of TURN servers.
 * @param {RTCIceTransportPolicy} [options.iceTransportPolicy] - ICE transport policy.
 * @param {Boolean} [options.spy] - Whether this is a spy peer.
 *
 * @throws {Error} if device is not supported.
 *
 * @emits {request: Object, callback: Function, errback: Function} request
 * @emits {notification: Object} notify
 * @emits {peer: Peer} newpeer
 * @emits {originator: String, [appData]: Any} close
 */

class Room(
    options: RoomOptions,
    private var logger: Logger = Logger("Transport")
): EnhancedEventEmitter(logger){
    // Computed settings.
    private lateinit var _settings: RoomOptions

    // Room state
    private var _state = RoomState.NEW

    //My mediasoup Peer name.
    private var _peerName: String = String()

    // Map of Transports indexed by id.
    // @type {map<Number, Transport>}
    private val _transports: HashMap<Int, Transport> = HashMap()

    // Map of Producers indexed by id.
    // @type {map<Number, Producer>}
    private val _producers: HashMap<Int, Any> = HashMap()

    // Map of Peers indexed by name.
    // @type {map<String, Peer>}
    private val _peers: HashMap<String, Peer> = HashMap()

    // Extended RTP capabilities.
    // @type {Object}
    private var _extendedRtpCapabilities: RTCExtendedRtpCapabilities = RTCExtendedRtpCapabilities()

    // Whether we can send audio/video based on computed extended RTP
    // capabilities.
    // @type {Object}
    private val _canSendByKind = CanSendByKind()

    init {
        logger.debug("constructor() [options:$options]")

        // Computed settings.
        _settings = RoomOptions()
    }

    /**
     * Whether the Room is joined.
     *
     * @return {Boolean}
     */
    fun joined(): Boolean{
        return this._state === RoomState.JOINED
    }

    /**
     * Whether the Room is closed.
     *
     * @return {Boolean}
     */
    fun closed(): Boolean{
        return this._state === RoomState.CLOSED
    }

    /**
     * My mediasoup Peer name.
     *
     * @return {String}
     */
    fun peerName(): String{
        return this._peerName
    }

    /**
     * The list of Transports.
     *
     * @return {Array<Transport>}
     */
    fun transports(): ArrayList<Transport>{
        return ArrayList(_transports.values)
    }

    /**
     * The list of Producers.
     *
     * @return {Array<Producer>}
     */
    fun producers(): ArrayList<Any>{
        return ArrayList(_producers.values)
    }

    /**
     * The list of Peers.
     *
     * @return {Array<Peer>}
     */
    fun peers(): ArrayList<Peer>{
        return ArrayList(_peers.values)
    }

    /**
     * fun  the Transport with the given id.
     *
     * @param {Number} id
     *
     * @return {Transport}
     */
    fun getTransportById(id: Int): Transport?{
        return this._transports[id]
    }

    /**
     * fun  the Producer with the given id.
     *
     * @param {Number} id
     *
     * @return {Producer}
     */
    fun getProducerById(id: Int): Any?{
        return this._producers[id]
    }

    /**
     * fun  the Peer with the given name.
     *
     * @param {String} name
     *
     * @return {Peer}
     */
    fun getPeerByName(id: String): Peer?{
        return this._peers[id]
    }

    /**
     * Start the procedures to join a remote room.
     * @param {String} peerName - My mediasoup Peer name.
     * @param {Any} [appData] - App custom data.
     * @return {Promise}
     */
    fun join(peerName: String,
             appData: Any? = null): Observable<Any> {
        logger.debug("join() [peerName:$peerName]")

        if (this._state != RoomState.NEW && this._state != RoomState.CLOSED) {
            return  Observable.create {
                it.onError(InvalidStateError("invalid state ${this._state.v}"))
            }
        }

        this._peerName = peerName
        this._state = RoomState.JOINING

        var roomSettings: QueryRoomResponse? = null

        return Observable.just(Unit)
            .flatMap{
                // If Room settings are provided don"t query them.
                if (this._settings.roomSettings != null){
                    roomSettings = this._settings.roomSettings
                    Observable.create{
                        //next
                        it.onNext(Unit)
                    }
                }else{
                     this._sendRequest(QueryRoomRequest())
                         .flatMap { response ->
                             roomSettings = JSON.parseObject(response,QueryRoomResponse::class.java)

                             logger.debug("join() | got Room settings:response")

                             Observable.create(ObservableOnSubscribe<Unit> {
                                 //next
                                 it.onNext(Unit)
                             })
                         }
                }
            }.flatMap{
                Handler.getNativeRtpCapabilities()
            }.flatMap { nativeRtpCapabilities ->
                val nativeRtpCapabilitiesString = JSON.toJSONString(nativeRtpCapabilities)
                logger.debug("join() | native RTP capabilities:$nativeRtpCapabilitiesString")

                // Get extended RTP capabilities.
                this._extendedRtpCapabilities = getExtendedRtpCapabilities(
                    nativeRtpCapabilities, roomSettings?.rtpCapabilities!!)

                val extendedRtpCapabilitiesString = JSON.toJSONString(this._extendedRtpCapabilities)
                logger.debug("join() | extended RTP capabilities:$extendedRtpCapabilitiesString)")

                // Check unsupported codecs.
                val unsupportedRoomCodecs = getUnsupportedCodecs(roomSettings?.rtpCapabilities!!,
                roomSettings?.mandatoryCodecPayloadTypes,
                this._extendedRtpCapabilities)

                if (unsupportedRoomCodecs.isNotEmpty()){
                    logger.error(
                        "${unsupportedRoomCodecs.size} mandatory room codecs not supported")

                    throw UnsupportedError(
                            "mandatory room codecs not supported", unsupportedRoomCodecs)
                }

                // Check whether we can send audio/video.
                this._canSendByKind.audio = canSend("audio", this._extendedRtpCapabilities)
                this._canSendByKind.video = canSend("video", this._extendedRtpCapabilities)

                // Generate our effective RTP capabilities for receiving media.
                val effectiveLocalRtpCapabilities = getRtpCapabilities(this._extendedRtpCapabilities)

                val effectiveLocalRtpCapabilitiesString = JSON.toJSONString(effectiveLocalRtpCapabilities)
                logger.debug("join() | effective local RTP capabilities for receiving:$effectiveLocalRtpCapabilitiesString")

                val joinRequest = JoinRequest()
                joinRequest.peerName = this._peerName
                joinRequest.rtpCapabilities = effectiveLocalRtpCapabilities
                joinRequest.spy = this._settings.spy
                joinRequest.appData = appData

                this._sendRequest(joinRequest)
                    .flatMap { response ->
                        var joinResponse = JSON.parseObject(response,JoinResponse::class.java)

                        Observable.create(ObservableOnSubscribe<ArrayList<PeerData>> {
                            //next
                            it.onNext(joinResponse.peers)
                        })
                    }
            }.flatMap { peers ->
                // Handle Peers already existing in the room.
                for (peerData in peers) {
                    try {
                        this._handlePeerData(peerData)
                    } catch (error: Exception) {
                        logger.error("join() | error handling Peer:${error.message}")
                    }
                }

                this._state = RoomState.JOINED

                logger.debug("join() | joined the Room")

                // Return the list of already existing Peers.
                Observable.create(ObservableOnSubscribe<Any> {
                    it.onNext(this.peers())
                })
            }
    }


    /**
     * Leave the Room.
     *
     * @param {Any} [appData] - App custom data.
     */
    fun leave(appData: Any? = null){
        logger.debug("leave()")

        if (this.closed())
            return

        // Send a notification.
        var leaveNotification = LeaveNotification()
        leaveNotification.appData = appData
        this._sendNotification(leaveNotification)

        // Set closed state after sending the notification (otherwise the
        // notification won"t be sent).
        this._state = RoomState.CLOSED

        this.safeEmit("close", "local", appData!!)

        // Close all the Transports.
        for (transport in ArrayList(this._transports.values)){
            transport.close()
        }

        // Close all the Producers.
        for (producer in ArrayList(this._producers.values)){
            //producer.close()
        }

        // Close all the Peers.
        for (peer in ArrayList(this._peers.values)){
            peer.close()
        }
    }


    /**
     * The remote Room was closed or our remote Peer has been closed.
     * Invoked via remote notification or via API.
     *
     * @param {Any} [appData] - App custom data.
     */
    fun remoteClose(appData: Any? = null ){
        logger.debug("remoteClose()")

        if (this.closed())
            return

        this._state = RoomState.CLOSED

        this.safeEmit("close", "remote", appData!!)

        // Close all the Transports.
        for (transport in ArrayList(this._transports.values)){
            transport.remoteClose(null, true)
        }

        // Close all the Producers.
        for (producer in ArrayList(this._producers.values)){
            //producer.remoteClose()
        }

        // Close all the Peers.
        for (peer in ArrayList(this._peers.values)) {
            peer.remoteClose()
        }
    }

    /**
     * Whether we can send audio/video.
     *
     * @param {String} kind - "audio" or "video".
     *
     * @return {Boolean}
     */
    @Throws(Exception::class)
    fun canSend(kind: String): Boolean{
        if (kind !== "audio" && kind !== "video")
            throw Exception("invalid kind $kind")

        if (!this.joined() || this._settings.spy)
            return false

        when (kind) {
            "audio" -> {
                return this._canSendByKind.audio
            }
            "video" -> {
                return this._canSendByKind.video
            }
            else -> return  false
        }
    }

    /**
     * Creates a Transport.
     *
     * @param {String} direction - Must be "send" or "recv".
     * @param {Any} [appData] - App custom data.
     *
     * @return {Transport}
     *
     * @throws {InvalidStateError} if not joined.
     * @throws {TypeError} if wrong arguments.
     */
    @Throws(Exception::class)
    fun createTransport(direction: String,
                        appData: Any? = null): Transport{
        logger.debug("createTransport() [direction:$direction]")

        if (!this.joined()){
            throw InvalidStateError("invalid state ${this._state.v}")
        }else if (direction !== "send" && direction !== "recv"){
            throw Exception("invalid direction $direction")
        }else if(direction === "send" && this._settings.spy){
            throw Exception("a spy peer cannot send media to the room")
        }

        // Create a new Transport.
        val transport = Transport(
            direction, this._extendedRtpCapabilities, this._settings, appData)

        // Store it.
        this._transports.put(transport.id(), transport)

        transport.on("@request") {
            val method = it[0] as String
            val data = it[1] as MediasoupRequest
            val callback = it[2] as Function1<Any, Unit>
            val errback = it[3] as Function1<Any, Unit>

            this._sendRequest(data)
                .subscribe(
                    {
                        callback.invoke(it)
                    },
                    {
                        errback.invoke(it)
                    })
        }

        transport.on("@notify"){
            val method = it[0] as String
            val data = it[1] as MediasoupNotify
            this._sendNotification(data)
        }

        transport.on("@close"){
            this._transports.remove(transport.id())
        }

        return transport
    }
    
    fun _sendRequest(request: MediasoupRequest): Observable<String> {
        // Should never happen.
        // Ignore if closed.
        if (this.closed())
        {
            logger.error("_sendRequest() | Room closed [method:${request.method}]")

            return  Observable.create {
                it.onError(InvalidStateError("Room closed"))
            }
        }

        logger.debug("_sendRequest() [method:${request.method}]")

        return Observable.create {
            var done = false

            val timer: Timer= Timer()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    logger.error("request failed [method:${request.method}]: timeout")

                    done = true
                    it.onError(TimeoutError("timeout"))
                }
            }, this._settings.requestTimeout)

            //success callback
            var callback = callback@{ response: String ->
                if (done)
                    return@callback

                done = true
                timer.cancel()

                if (this.closed())
                {
                    logger.error("request failed [method:${request.method}]: Room closed")

                    it.onError(Error("Room closed"))

                    return@callback
                }

                logger.debug("request succeeded [method:${request.method}, response:$response]")
                
                it.onNext(response)
            }

            //error callback
            var errback = errback@{ error: Error ->
                if (done)
                    return@errback

                done = true
                timer.cancel()

                if (this.closed())
                {
                    logger.error("request failed [method:${request.method}]: Room closed")

                    it.onError(Error("Room closed"))

                    return@errback
                }

                // Make sure message is an Error.
//                if (!(error is Error))
//                    error = new Error(String(error))

                logger.error("request failed [method:${request.method}]:${error.message}")

                it.onError(error)
            }

            //need to change obj to jsonString
            this.safeEmit("request",JSON.toJSONString(request), callback, errback)
        }
    }

    fun _sendNotification(notification: MediasoupNotify){
        // Ignore if closed.
        if (this.closed())
            return
        
        logger.debug("_sendNotification() [method:${notification.method}")

        this.safeEmit("notify", notification)
    }

    fun _handlePeerData(peerData: PeerData){
        val peer = Peer(peerData.name, peerData.appData)

        // Store it.
        this._peers.set(peer.name, peer)

        peer.on("@close"){
            this._peers.remove(peer.name)
        }

        // Add consumers.
        for (consumerData in peerData.consumers) {
            try{
                this._handleConsumerData(consumerData, peer)
            }
            catch (error: Exception){
                logger.error("error handling existing Consumer in Peer: ${error.message}")
            }
        }

        // If already joined emit event.
        if (this.joined())
            this.safeEmit("newpeer", peer)
    }

    fun _handleConsumerData(consumerData: ConsumerData,
                            peer: Peer) {
        //val consumer = Consumer(id, kind, rtpParameters, peer, appData)
        val consumer = Consumer()
        val supported = canReceive(consumerData.rtpParameters, this._extendedRtpCapabilities)

//        if (supported)
//            consumer.setSupported(true)
//
//        if (consumerData.paused)
//            consumer.remotePause()

        peer.addConsumer(consumer)
    }
}

enum class RoomState(val v: String) {
    NEW("new"),
    JOINING("joining"),
    JOINED("joined"),
    CLOSED("closed")
}

class CanSendByKind{
    var audio: Boolean = false
    var video: Boolean = false
}
