package com.versatica.mediasoup.handlers.sdp

import com.dingsoft.sdptransform.SdpTransform
import com.dingsoft.sdptransform.SessionDescription

/**
 * @author wolfhan
 */

object CommonUtils {
    /**
     * Extract RTP capabilities from a SDP.
     *
     * @param {SessionDescription} sdpObj - SDP Object generated by sdp-transform.
     * @return {RTCRtpCapabilities}
     */
    fun extractRtpCapabilities(sdpObj: SessionDescription): RTCRtpCapabilities {
        val codecsMap = HashMap<Any?, RTCRtpCodecCapability>()
        val headerExtensions = arrayListOf<RTCRtpHeaderExtensionCapability>()
        var gotAudio = false
        var gotVideo = false

        loop@ for (m in sdpObj.media) {
            val kind = m.type

            when (kind) {
                "audio" -> {
                    if (gotAudio)
                        continue@loop
                    gotAudio = true
                }
                "video" -> {
                    if (gotVideo)
                        continue@loop
                    gotVideo = true
                }
                else -> {
                    continue@loop
                }
            }

            // Get codecs.
            val rtpList = m.rtp
            if (rtpList != null && rtpList.isNotEmpty()) {
                for (rtp in rtpList) {
                    val codec = RTCRtpCodecCapability(
                        mimeType = "$kind/${rtp.codec}",
                        clockRate = rtp.rate,
                        channels = rtp.encoding?.toIntOrNull()
                    )
                    codec.name = rtp.codec
                    codec.kind = kind
                    codec.preferredPayloadType = rtp.payload

                    if (codec.kind != "audio")
                        codec.channels = null
                    else if (codec.channels == null)
                        codec.channels = 1

                    codecsMap[codec.preferredPayloadType] = codec
                }
            }

            // Get codec parameters.
            val fmtpList = m.fmtp
            if (fmtpList != null && fmtpList.isNotEmpty()) {
                for (fmtp in fmtpList) {
                    val parameters = SdpTransform().parseParams(fmtp.config)
                    val codec = codecsMap[fmtp.payload] ?: continue
                    codec.parameters = parameters
                }
            }

            // Get RTCP feedback for each codec.
            val fbList = m.rtcpFb
            if (fbList != null && fbList.isNotEmpty()) {
                for (fb in fbList) {
                    val codec = codecsMap[fb.payload] ?: continue
                    val feedback = RtcpFeedback(
                        type = fb.type,
                        parameter = fb.subtype
                    )
                    codec.rtcpFeedback?.add(feedback)
                }
            }

            // Get RTP header extensions.
            val extList = m.ext
            if (extList != null && extList.isNotEmpty()) {
                for (ext in extList) {
                    val headerExtension = RTCRtpHeaderExtensionCapability(
                        uri = ext.uri
                    )
                    headerExtension.kind = kind
                    headerExtension.preferredId = ext.value
                    headerExtensions.add(headerExtension)
                }
            }
        }

        return RTCRtpCapabilities(codecsMap.values, headerExtensions)
    }


    /**
     * Extract DTLS parameters from a SDP.
     *
     * @param {SessionDescription} sdpObj - SDP Object generated by sdp-transform.
     * @return {RTCDtlsParameters}
     */
    fun extractDtlsParameters(sdpObj: SessionDescription): RTCDtlsParameters {
        val media = getFirstActiveMediaSection(sdpObj)
        val fingerprint = media?.fingerprint ?: sdpObj.fingerprint

        val role = when (media?.setup) {
            "active" -> RTCDtlsRole.client
            "passive" -> RTCDtlsRole.server
            "actpass" -> RTCDtlsRole.auto
            else -> RTCDtlsRole.auto
        }

        val dtlsParameters = RTCDtlsParameters(role = role)
        val fingerprintObj = RTCDtlsFingerprint(algorithm = fingerprint?.type, value = fingerprint?.hash)
        val fingerprints = arrayListOf<RTCDtlsFingerprint>()
        fingerprints.add(fingerprintObj)
        dtlsParameters.fingerprints = fingerprints
        return dtlsParameters
    }

    /**
     * Get the first acive media section.
     *
     * @private
     * @param {SessionDescription} sdpObj - SDP Object generated by sdp-transform.
     * @return {SessionDescription.Media} SDP media section as parsed by sdp-transform.
     */
    fun getFirstActiveMediaSection(sdpObj: SessionDescription): SessionDescription.Media? {
        val mediaList = sdpObj.media
        if (mediaList.isNotEmpty()) {
            return mediaList.find {
                it.iceUfrag != null && it.port != 0
            }
        }
        return null
    }
}

data class RtcpFeedback(
    val type: String,
    val parameter: String? = null
)