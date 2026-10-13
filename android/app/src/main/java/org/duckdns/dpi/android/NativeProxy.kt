package org.duckdns.dpi.android

/** JNI entry of ByeDPI (ciadpi). Only ever called inside the ":dpi" process. */
object NativeProxy {
    init { System.loadLibrary("ciadpi-jni") }

    /** Blocks until the proxy exits. [args] are ciadpi's options without the program name. */
    @JvmStatic external fun run(args: Array<String>, logPath: String?): Int
}
