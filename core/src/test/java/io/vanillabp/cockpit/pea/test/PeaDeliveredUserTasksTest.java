package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks.DeliveredUserTask;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The memory of a node has an end, and what happens when it is reached is the difference between
 * a cockpit which loses a report and an application which runs out of heap.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaDeliveredUserTasksTest {

  private static DeliveredUserTask userTask(
      final String taskId) {

    return new DeliveredUserTask(
        new UserTaskReference(
            TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711", "instance-1", taskId, TestModels.USER_TASK_FORM, TestModels.USER_TASK_ELEMENT), UserTaskDetailsPrefill
                .builder().build());

  }

  @Test
  @DisplayName("The oldest user task is forgotten when the memory is full")
  public void theOldestUserTaskIsForgotten() {

    final var remembered = new PeaDeliveredUserTasks(2);

    remembered.remember(userTask("task-1"));
    remembered.remember(userTask("task-2"));
    remembered.remember(userTask("task-3"));

    assertEquals(2, remembered.capacity());
    assertTrue(remembered.of("task-1").isEmpty(), "the oldest one made room");
    assertTrue(remembered.of("task-2").isPresent());
    assertTrue(remembered.of("task-3").isPresent());

  }

  @Test
  @DisplayName("A workflow is new to a node once")
  public void aWorkflowIsNewOnce() {

    final var remembered = new PeaDeliveredUserTasks(2);

    assertTrue(remembered.workflowReportedForTheFirstTime(TestModels.ADAPTER_ID, "instance-1"));
    assertTrue(
        !remembered.workflowReportedForTheFirstTime(TestModels.ADAPTER_ID, "instance-1"),
        "a workflow which was reported is not reported again");
    assertTrue(remembered.workflowReportedForTheFirstTime("another-pea", "instance-1"));

  }

}
