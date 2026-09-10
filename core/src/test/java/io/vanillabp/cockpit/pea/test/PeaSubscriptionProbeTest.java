package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.bpmcrafters.processengineapi.task.SubscribeForTaskCmd;
import dev.bpmcrafters.processengineapi.task.TaskHandler;
import dev.bpmcrafters.processengineapi.task.TaskType;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * That the in-memory engine of the VanillaBP Process-Engine-API adapter still hands a delivered
 * user task to ONE subscription, the way the Process-Engine-API's own reference engine adapter
 * does.
 * <p>
 * The whole design of this half rests on that: an extension cannot subscribe next to the workflow
 * application, because it would either see nothing or take the task away from it, which is why
 * there is a port waiting for a seam in the adapter instead (decision 5 in the repository's
 * DECISIONS.md). The engine used here is a test double of the adapter, and a test double which
 * quietly grew a second seat would leave that design resting on nothing - so what this test
 * guards is the double's fidelity, not the API. Entry 1 of the repository's
 * <code>GAPS.md</code> holds the evidence from the reference adapter and what follows for the
 * cockpit.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaSubscriptionProbeTest {

  @Test
  @DisplayName("The adapter's in-memory engine still delivers a user task to one subscription only")
  public void theInMemoryEngineDeliversToOneSubscriptionOnly() throws Exception {

    final var engine = new InMemoryProcessEngine();
    final var deliveredToTheApplication = new LinkedList<String>();
    final var deliveredToTheExtension = new LinkedList<String>();

    subscribe(engine, deliveredToTheApplication::add);
    subscribe(engine, deliveredToTheExtension::add);

    engine
        .deliverTask(
            "task-1", TestModels.USER_TASK_FORM, TestModels.BPMN_PROCESS_ID, Map.of("id", "4711"));

    assertEquals(
        List.of("task-1"), deliveredToTheApplication, "the first subscription gets the task");
    assertTrue(
        deliveredToTheExtension.isEmpty(),
        "a second subscription for the same task definition is never delivered to");

  }

  private static void subscribe(
      final InMemoryProcessEngine engine,
      final Consumer<String> taskIds) throws Exception {

    final TaskHandler handler = (
        taskInformation,
        payload) -> taskIds.accept(taskInformation.getTaskId());
    engine
        .subscribeForTask(
            new SubscribeForTaskCmd(
                Map.of(), TaskType.USER, TestModels.USER_TASK_FORM, Set.of(), handler, (Consumer<String>) taskId -> {
                }))
        .get();

  }

}
