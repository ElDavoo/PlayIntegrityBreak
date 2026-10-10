package icu.nullptr.playintegritybreak.core.playstore

import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap

/** Reads the objects of a running process through their fields, by reflection. */
internal object ObjectGraph {
    private const val MAX_OBJECTS = 300_000
    private const val STRING_DEPTH = 4

    /**
     * Visits the objects reachable from [root] through fields, nearest first, up to [maxDepth] levels and [MAX_OBJECTS]
     * objects. Stops when [visit] returns true.
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
            forEachFieldValue(o, includeStatic = true) { value ->
                if (value !is String && !seen.containsKey(value)) {
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
            forEachFieldValue(o, includeStatic = false) { value ->
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

    /** Calls [action] with the value of each non-null reference field of [o], its superclasses' included. */
    private inline fun forEachFieldValue(o: Any, includeStatic: Boolean, action: (Any) -> Unit) {
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            for (field in c.declaredFields) {
                if (field.type.isPrimitive || (!includeStatic && Modifier.isStatic(field.modifiers))) continue
                val value = runCatching { field.isAccessible = true; field.get(o) }.getOrNull() ?: continue
                action(value)
            }
            c = c.superclass
        }
    }
}
