package icu.nullptr.playintegritybreak.core.playstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectGraphTest {
    private class Target

    /** Like a collection: what it holds is in an array (here the JVM's own collections cannot be read by reflection). */
    private class Table(val slots: Array<Any?>)

    private class Root(val table: Table, val numbers: IntArray)

    @Test
    fun findsObjectsHeldInArrays() {
        val target = Target()
        val root = Root(Table(arrayOf(null, "text", arrayOf<Any?>(target))), intArrayOf(1, 2))
        val visited = ArrayList<Any>()
        ObjectGraph.reachableFrom(root, maxDepth = 12) { visited.add(it); it === target }
        // root, table, the int array (it holds no objects), table's array, the inner array, the target; strings are not visited.
        assertEquals(listOf(Root::class.java, Table::class.java, IntArray::class.java, Array<Any?>::class.java, Array<Any?>::class.java, Target::class.java),
            visited.map { it.javaClass })
    }

    @Test
    fun anArrayIsALevel() {
        val target = Target()
        val root = Root(Table(arrayOf(target)), intArrayOf())
        var found = false
        // root -> table -> array -> target is three levels down.
        ObjectGraph.reachableFrom(root, maxDepth = 2) { found = found || it === target; false }
        assertTrue(!found)
        ObjectGraph.reachableFrom(root, maxDepth = 3) { found = found || it === target; false }
        assertTrue(found)
    }

    @Test
    fun readsStringsInArrays() {
        val root = Root(Table(arrayOf("[MEETS_BASIC_INTEGRITY]", arrayOf<Any?>("nested"))), intArrayOf())
        assertEquals(setOf("[MEETS_BASIC_INTEGRITY]", "nested"), ObjectGraph.stringsIn(root))
    }
}
