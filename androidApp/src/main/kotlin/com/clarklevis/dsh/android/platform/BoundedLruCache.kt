package com.clarklevis.dsh.android.platform

internal class BoundedLruCache<K, V>(
    private val maximumWeight: Long,
    private val weightOf: (V) -> Long
) {
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    private var weight = 0L

    fun get(key: K): V? = values[key]

    /** 返回本次因超预算被淘汰的 key，调用方据此同步派生状态。 */
    fun put(key: K, value: V): List<K> {
        val evicted = mutableListOf<K>()
        values.remove(key)?.let { weight -= weightOf(it) }
        values[key] = value
        weight += weightOf(value)
        val iterator = values.entries.iterator()
        while (weight > maximumWeight && iterator.hasNext()) {
            val entry = iterator.next()
            weight -= weightOf(entry.value)
            evicted += entry.key
            iterator.remove()
        }
        return evicted
    }

    fun remove(key: K): V? = values.remove(key)?.also { weight -= weightOf(it) }

    fun retainKeys(keys: Set<K>) {
        val iterator = values.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in keys) {
                weight -= weightOf(entry.value)
                iterator.remove()
            }
        }
    }

    fun snapshot(): Map<K, V> = values.toMap()
    fun currentWeight(): Long = weight
}
