package android.util
object Base64 {
    const val DEFAULT = 0
    fun decode(value: String, flags: Int): ByteArray = java.util.Base64.getMimeDecoder().decode(value)
}
