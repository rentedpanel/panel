package com.pro.injector

object NativeBridge {
    init {
        try {
            System.loadLibrary("injector")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    external fun init(pid: Int): Boolean
    external fun setFeature(id: Int, on: Boolean): Boolean
    external fun isSupported(): Boolean
    external fun stop()
}
