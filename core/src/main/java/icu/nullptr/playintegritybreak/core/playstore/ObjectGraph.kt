package icu.nullptr.playintegritybreak.core.playstore

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the objects of a running process through their fields, by reflection, and through the elements of object arrays:
 * what a collection holds (a map's table, a list's elements, a protobuf repeated field) is in an array.
 */
internal object ObjectGraph {
    private const val MAX_OBJECTS = 300_000
    private const val STRING_DEPTH = 4

    /**
     * Visits the objects reachable from [root], nearest first, up to [maxDepth] levels and [MAX_OBJECTS] objects. An array
     * is a level of its own: a map's values are three levels below the map (table, entry, value). Stops when [visit]
     * returns true.
     */
    fun reachableFrom(root: Any, maxDepth: Int, visit: (Any) -> Boolean) {
        val queue = ArrayDeque<Pair<Any, Int>>()
        val seen = IdentityHashMap<Any, Boolean>()
        queue.add(root to 0)
        seen[root] = true
        while (queue.isNotEmpty() && seen.size <= MAX_OBJECTS) {
            val (o, depth) = queue.remove()
            if (visit(o)) return
            if (depth >= maxDepth) continue
            forEachReference(o, includeStatic = true) { value ->
                if (value !is String && seen.size <= MAX_OBJECTS && !seen.containsKey(value)) {
                    seen[value] = true
                    queue.add(value to depth + 1)
                }
            }
        }
    }

    /** Every string held by [root] and by the objects it holds, a few levels down. */
    fun stringsIn(root: Any?): Set<String> {
        val strings = LinkedHashSet<String>()
        if (root == null) return strings
        val queue = ArrayDeque<Pair<Any, Int>>()
        val seen = IdentityHashMap<Any, Boolean>()
        queue.add(root to 0)
        seen[root] = true
        while (queue.isNotEmpty()) {
            val (o, depth) = queue.remove()
            forEachReference(o, includeStatic = false) { value ->
                if (value is String) {
                    strings.add(value)
                } else if (depth < STRING_DEPTH && !seen.containsKey(value)) {
                    seen[value] = true
                    queue.add(value to depth + 1)
                }
            }
        }
        return strings
    }

    /**
     * Calls [action] with each non-null element of [o] if it is an object array, or else with the value of each non-null
     * reference field of [o], its superclasses' included.
     */
    private inline fun forEachReference(o: Any, includeStatic: Boolean, action: (Any) -> Unit) {
        if (o is Array<*>) {
            for (element in o) if (element != null) action(element)
            return
        }
        val fields = referenceFields(o.javaClass)
        for (field in if (includeStatic) fields.all else fields.instance) {
            val value = runCatching { field.get(o) }.getOrNull() ?: continue
            action(value)
        }
    }

    /** The reference fields of a class and its superclasses that can be read: all of them, and the non-static ones. */
    private class ReferenceFields(val all: Array<Field>, val instance: Array<Field>)

    /** Per class: a search reads the same few thousand classes over and over. */
    private val referenceFieldsCache = ConcurrentHashMap<Class<*>, ReferenceFields>()

    private fun referenceFields(type: Class<*>): ReferenceFields = referenceFieldsCache.getOrPut(type) {
        val all = ArrayList<Field>()
        var c: Class<*>? = type
        while (c != null && c != Any::class.java) {
            c.declaredFields.filterTo(all) { !it.type.isPrimitive && runCatching { it.isAccessible = true }.isSuccess }
            c = c.superclass
        }
        ReferenceFields(all.toTypedArray(), all.filter { !Modifier.isStatic(it.modifiers) }.toTypedArray())
    }
}
