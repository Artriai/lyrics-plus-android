package android.content
open class Context {
    val applicationContext: Context get() = this
    val cacheDir: java.io.File get() = error("Host live tests must not enable Android disk caching")
}
