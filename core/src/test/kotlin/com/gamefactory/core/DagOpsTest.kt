package com.gamefactory.core

import com.gamefactory.core.dag.DagOps
import com.gamefactory.core.model.TaskCategory
import com.gamefactory.core.model.TaskNode
import com.gamefactory.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DagOpsTest {

    private fun task(id: String, deps: List<String> = emptyList(), status: TaskStatus = TaskStatus.PENDING) =
        TaskNode(id = id, title = id, deps = deps, category = TaskCategory.CORE, status = status)

    @Test
    fun `topological order respects dependencies`() {
        val tasks = listOf(
            task("c", deps = listOf("b")),
            task("a"),
            task("b", deps = listOf("a"))
        )
        val order = DagOps.topologicalOrder(tasks).map { it.id }
        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test(expected = DagOps.DagValidationException::class)
    fun `cycle is rejected`() {
        DagOps.topologicalOrder(listOf(task("a", listOf("b")), task("b", listOf("a"))))
    }

    @Test
    fun `hasCycle detects and does not throw`() {
        assertTrue(DagOps.hasCycle(listOf(task("a", listOf("a")))))
        assertFalse(DagOps.hasCycle(listOf(task("a"), task("b", listOf("a")))))
    }

    @Test(expected = DagOps.DagValidationException::class)
    fun `unknown dependency is rejected`() {
        DagOps.topologicalOrder(listOf(task("a", listOf("ghost"))))
    }

    @Test(expected = DagOps.DagValidationException::class)
    fun `duplicate ids are rejected`() {
        DagOps.topologicalOrder(listOf(task("a"), task("a")))
    }

    @Test
    fun `ready tasks only include deps-done tasks`() {
        val tasks = listOf(
            task("a", status = TaskStatus.DONE),
            task("b", deps = listOf("a")),
            task("c", deps = listOf("b")),
            task("d")
        )
        val ready = DagOps.readyTasks(tasks).map { it.id }.sorted()
        assertEquals(listOf("b", "d"), ready)
    }

    @Test
    fun `isComplete only when all done`() {
        assertFalse(DagOps.isComplete(listOf(task("a"), task("b"))))
        assertTrue(DagOps.isComplete(listOf(task("a", status = TaskStatus.DONE), task("b", status = TaskStatus.DONE))))
        assertFalse(DagOps.isComplete(emptyList()))
    }
}
