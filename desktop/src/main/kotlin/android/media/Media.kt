@file:Suppress("unused")

package android.media

object MediaFormat {
    const val MIMETYPE_VIDEO_AVC = "video/avc"
    const val MIMETYPE_VIDEO_HEVC = "video/hevc"
    const val MIMETYPE_VIDEO_AV1 = "video/av01"
}

/** VLC decodes everything the Android codec list could report, HEVC and AV1 included. */
class MediaCodecList(@Suppress("UNUSED_PARAMETER") kind: Int) {
    class CodecInfo(val isEncoder: Boolean, val supportedTypes: Array<String>)

    val codecInfos: Array<CodecInfo> = arrayOf(
        CodecInfo(false, arrayOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AV1)),
    )

    companion object {
        const val REGULAR_CODECS = 0
        const val ALL_CODECS = 1
    }
}
