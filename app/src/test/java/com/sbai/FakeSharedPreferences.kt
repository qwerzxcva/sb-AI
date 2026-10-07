package com.sbai

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯 JVM 的 SharedPreferences 假实现（SharedPreferences 是 Java 接口，无需 Android 框架）。
 * 用于 RuleStore / VpnRuntimeState 的跨进程通道测试。
 */
class FakeSharedPreferences : SharedPreferences {

    private val data = ConcurrentHashMap<String, Any?>()

    override fun getAll(): Map<String, *> = HashMap(data)

    override fun getString(key: String?, defValue: String?): String? =
        data[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValue: MutableSet<String>?): MutableSet<String>? =
        data[key] as? MutableSet<String> ?: defValue

    override fun getInt(key: String?, defValue: Int): Int =
        data[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        data[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        data[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = linkedSetOf<String?>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            applyPut(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            applyPut(key, values?.toMutableSet())

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            applyPut(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            applyPut(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            applyPut(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            applyPut(key, value)

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            removed += key
        }

        override fun clear(): SharedPreferences.Editor = apply {
            pending.clear()
            removed += data.keys.toList()
        }

        override fun commit(): Boolean {
            removed.forEach { data.remove(it) }
            pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            pending.clear()
            removed.clear()
            return true
        }

        override fun apply() {
            commit()
        }

        private fun applyPut(key: String?, value: Any?): SharedPreferences.Editor {
            check(key != null) { "SharedPreferences key must not be null" }
            if (value == null) removed += key else pending[key] = value
            return this
        }
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        // no-op for tests
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        // no-op for tests
    }
}
