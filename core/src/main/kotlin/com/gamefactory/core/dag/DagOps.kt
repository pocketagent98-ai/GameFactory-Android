package com.gamefactory.core.dag

import com.gamefactory.core.model.TaskNode

/** Pure DAG operations: topological order, cycle detection, ready tasks. */
object DagOps {

    class DagValidationException(message: String) : RuntimeException(message)

    /** Kahn's algorithm. Throws on cycles or unknown dependencies. */
    fun topologicalOrder(tasks: List<TaskNode>): List<TaskNode> {
        val byId = tasks.associateBy { it.id }
        if (byId.size != tasks.size) {
            val dup = tasks.groupBy { it.id }.filterValues { it.size > 1 }.keys
            throw DagValidationException("duplicate task ids: $dup")
        }
        val indegree = tasks.associate { it.id to 0 }.toMutableMap()
        val dependents = tasks.associate { it.id to mutableListOf<String>() }
        for (t in tasks) {
            for (d in t.deps) {
                if (d !in byId) throw DagValidationException("task '${t.id}' depends on unknown task '$d'")
                indegree[t.id] = (indegree[t.id] ?: 0) + 1
                dependents[d]!!.add(t.id)
            }
        }
        val queue = ArrayDeque(tasks.filter { (indegree[it.id] ?: 0) == 0 }.map { it.id })
        val order = mutableListOf<TaskNode>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            order += byId[id]!!
            for (next in dependents[id]!!) {
                val remaining = (indegree[next] ?: 0) - 1
                indegree[next] = remaining
                if (remaining == 0) queue.add(next)
            }
        }
        if (order.size != tasks.size) {
            val stuck = tasks.filter { it.id !in order.map { o -> o.id } }.map { it.id }
            throw DagValidationException("dependency cycle involving: $stuck")
        }
        return order
    }

    fun hasCycle(tasks: List<TaskNode>): Boolean = try {
        topologicalOrder(tasks); false
    } catch (e: DagValidationException) {
        true
    }

    /** Tasks whose dependencies are all DONE and that are still PENDING. */
    fun readyTasks(tasks: List<TaskNode>): List<TaskNode> {
        val byId = tasks.associateBy { it.id }
        return tasks.filter { t ->
            t.status == com.gamefactory.core.model.TaskStatus.PENDING &&
                t.deps.all { d -> byId[d]?.status == com.gamefactory.core.model.TaskStatus.DONE }
        }
    }

    fun isComplete(tasks: List<TaskNode>): Boolean =
        tasks.isNotEmpty() && tasks.all { it.status == com.gamefactory.core.model.TaskStatus.DONE }
}
