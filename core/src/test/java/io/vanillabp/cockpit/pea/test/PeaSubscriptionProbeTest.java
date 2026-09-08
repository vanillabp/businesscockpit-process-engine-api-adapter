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
 * Why this extension does not open a task subscription of its own, held as a test rather than as
 * a sentence in a document.
 * <p>
 * The obvious way for an extension to watch user tasks would be to subscribe for the same task
 * definitions the VanillaBP Process-Engine-API adapter subscribes for. The Process-Engine-API
 * hands a task to ONE subscription, though: the engine picks the first subscription matching a
 * task and remembers it as the one active for it, so a second subscriber either sees nothing or
 * takes the task away from the workflow application - and which of the two it is depends on the
 * order two subscriptions happened to be registered in.
 * <p>
 * The engine here is the in-memory one the adapter ships. Its reference engine adapter for an
 * embedded Camunda 7 does the same thing (<code>EmbeddedPullUserTaskDelivery.refresh</code>
 * picks <code>subscriptions.firstOrNull { it.matches(task) }</code> and then
 * <code>activateSubscriptionForTask</code>), which is what makes this a property of the API
 * rather than of a fake. Entry 1 of the repository's <code>GAPS.md</code> says what follows for
 * the cockpit.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaSubscriptionProbeTest {

  @Test
  @DisplayName("A delivered user task reaches one subscription, so an extension cannot listen next to the adapter")
  public void aDeliveredUserTaskReachesOneSubscription() throws Exception {

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
