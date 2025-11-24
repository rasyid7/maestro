package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.workspace.WorkspaceExecutionPlanner.ExecutionPlan
import maestro.orchestra.workspace.WorkspaceExecutionPlanner.FlowSequence
import maestro.orchestra.WorkspaceConfig
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.io.File

class ShardingTest {

    @Test
    fun `makeChunkPlans distributes evenly with round robin when no times provided`() {
        val testCommand = TestCommand()
        val flows = (1..10).map { Paths.get("flow$it.yaml") }
        val plan = ExecutionPlan(
            flowsToRun = flows,
            sequence = FlowSequence(emptyList()),
            workspaceConfig = WorkspaceConfig()
        )

        val shards = 2
        val chunkPlans = testCommand.makeChunkPlans(plan, shards, false)

        assertThat(chunkPlans).hasSize(2)
        assertThat(chunkPlans[0].flowsToRun).hasSize(5)
        assertThat(chunkPlans[1].flowsToRun).hasSize(5)
        
        // Verify round robin
        // 0, 2, 4, 6, 8 -> shard 0
        // 1, 3, 5, 7, 9 -> shard 1
        // But the implementation uses groupBy { index % shards }
        // index 0 % 2 = 0
        // index 1 % 2 = 1
        assertThat(chunkPlans[0].flowsToRun.map { it.toString() }).containsExactly(
            "flow1.yaml", "flow3.yaml", "flow5.yaml", "flow7.yaml", "flow9.yaml"
        )
        assertThat(chunkPlans[1].flowsToRun.map { it.toString() }).containsExactly(
            "flow2.yaml", "flow4.yaml", "flow6.yaml", "flow8.yaml", "flow10.yaml"
        )
    }    @Test
    fun `makeChunkPlans distributes based on time estimates`() {
        val testCommand = TestCommand()
        val flows = listOf(
            Paths.get("flow1.yaml"), // 50
            Paths.get("flow2.yaml"), // 40
            Paths.get("flow3.yaml"), // 30
            Paths.get("flow4.yaml"), // 20
            Paths.get("flow5.yaml")  // 10
        )
        val plan = ExecutionPlan(
            flowsToRun = flows,
            sequence = FlowSequence(emptyList()),
            workspaceConfig = WorkspaceConfig()
        )
        
        val estimates = mapOf(
            "flow1.yaml" to 50L,
            "flow2.yaml" to 40L,
            "flow3.yaml" to 30L,
            "flow4.yaml" to 20L,
            "flow5.yaml" to 10L
        )

        val chunkPlans = testCommand.makeChunkPlans(plan, 2, false, estimates)
        
        // Greedy allocation check:
        // S0: 50
        // S1: 40
        // S1: 40+30 = 70
        // S0: 50+20 = 70
        // S0: 70+10 = 80 (since S0 index < S1 index when equal?)
        // Result: S0 has 1, 4, 5. S1 has 2, 3.
        
        val s0Flows = chunkPlans[0].flowsToRun.map { it.toString() }
        val s1Flows = chunkPlans[1].flowsToRun.map { it.toString() }
        
        assertThat(s0Flows).containsExactly("flow1.yaml", "flow4.yaml", "flow5.yaml")
        assertThat(s1Flows).containsExactly("flow2.yaml", "flow3.yaml")
    }
}
