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
    }}
