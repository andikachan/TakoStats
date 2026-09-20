package rikka.fpsmonitor.util

import android.annotation.SuppressLint

object SystemProperties {

    @SuppressLint("PrivateApi")
    fun get(key: String, defaultValue: String = ""): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, defaultValue) as String
        } catch (_: Exception) {
            defaultValue
        }
    }

    @SuppressLint("PrivateApi")
    fun getInt(key: String, defaultValue: Int = 0): Int {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            method.invoke(null, key, defaultValue) as Int
        } catch (_: Exception) {
            defaultValue
        }
    }
}
