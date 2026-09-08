package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.pea.PeaCockpitObserver;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks;
import io.vanillabp.cockpit.pea.PeaTaskMeta;
import io.vanillabp.cockpit.pea.PeaWorkflowModels;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.observation.PeaUserTaskObservation;

/**
 * What a delivered user task turns into, and what it does not turn into.
 * <p>
 * The integration tests of this repository run the same way through a booted application, driven
 * by the adapter's own delivery. What they cannot provoke is the other half: a task of a process
 * this application never deployed, a delivery which names no workflow aggregate or no BPMN
 * process at all, the end of a task this node never saw. Those are the cases where reporting
 * nothing is the right answer, and a test which cannot tell "reported nothing" from "was never
 * asked" would not notice them.
 * <p>
 * What an engine calls an identifier is not among them any more. The adapter translates the
 * scoped ids of a workflow module deployed with a prefix back before it builds an observation,
 * and holds itself to that.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaCockpitObserverTest {

  private RecordingPublisher publisher;

  private PeaDeliveredUserTasks deliveredUserTasks;

  private PeaCockpitObserver observer;

  @BeforeEach
  public void anApplicationWithOneDeployedProcess() {

    publisher = new RecordingPublisher();
    deliveredUserTasks = new PeaDeliveredUserTasks(10);
    observer = observerOf(TestModels.deployed());

  }

  private PeaCockpitObserver observerOf(
      final PeaWorkflowModels models) {

    return new PeaCockpitObserver(
        models, deliveredUserTasks, (
            adapterId,
            workflowModuleId,
            bpmnProcessId) -> "deployment-7", () -> publisher);

  }

  private static PeaUserTaskObservation delivery(
      final String taskId,
      final String reason,
      final Map<String, String> additionalMeta) {

    final var meta = new LinkedHashMap<String, String>();
    meta.put(CommonRestrictions.PROCESS_INSTANCE_ID, "instance-1");
    meta.put(PeaTaskMeta.BPMN_TASK_ID, TestModels.USER_TASK_ELEMENT);
    meta.putAll(additionalMeta);
    final var taskInformation = reason == null
        ? new TaskInformation(taskId, meta)
        : new TaskInformation(taskId, meta).withReason(reason);
    return new PeaUserTaskObservation(
        TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, TestModels.USER_TASK_FORM, "4711", taskInformation, Map
            .of("customer", "Bond"));

  }

  @Test
  @DisplayName("A delivered user task is reported with what the engine and the deployed BPMN say")
  public void aDeliveredUserTaskIsReported() {

    observer
        .userTaskDelivered(
            delivery(
                "task-1", TaskInformation.CREATE, Map
                    .of(
                        PeaTaskMeta.ASSIGNEE, "james", PeaTaskMeta.CANDIDATE_GROUPS, "drivers,dispatch",
                        PeaTaskMeta.DUE_DATE, "2026-09-09T12:00:00Z")));

    assertEquals(1, publisher.userTasks().size());
    final var reported = publisher.userTasks().getFirst();
    assertEquals(TestModels.ADAPTER_ID, reported.adapterId());
    assertEquals(TestModels.MODULE_ID, reported.workflowModuleId());
    assertEquals(TestModels.BPMN_PROCESS_ID, reported.bpmnProcessId());
    assertEquals("4711", reported.workflowAggregateId());
    assertEquals("instance-1", reported.workflowId());
    assertEquals("task-1", reported.userTaskId());
    assertEquals(TestModels.USER_TASK_FORM, reported.taskDefinition());
    assertEquals(TestModels.USER_TASK_ELEMENT, reported.bpmnTaskId());
    assertEquals(List.of(UserTaskEventKind.CREATED), publisher.userTaskKinds());
    assertEquals(List.of("task-1#create"), publisher.userTaskEventIds());
    assertTrue(
        publisher.transactions().stream().allMatch(EventTransaction.NEW::equals),
        "the engine delivers on a thread of its own, so every entry gets a transaction of its own");

    final var details = deliveredUserTasks.of("task-1").orElseThrow().details();
    assertEquals("A taxi ride", details.bpmnProcessName());
    assertEquals("Approve the ride", details.bpmnTaskName());
    assertEquals("james", details.assignee());
    assertEquals(List.of("drivers", "dispatch"), details.candidateGroups());
    assertEquals("2026-09-09T12:00Z", String.valueOf(details.dueDate()));
    assertEquals("deployment-7", details.bpmnProcessVersion());
    assertEquals("4711", details.businessId());
    assertEquals(Map.of("customer", "Bond"), details.variables());

  }

  @Test
  @DisplayName("The business case appears with the first task of it and is not created again with the next")
  public void theWorkflowIsReportedOnceItsFirstTaskAppeared() {

    observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of()));
    observer.userTaskDelivered(delivery("task-2", TaskInformation.CREATE, Map.of()));

    assertEquals(List.of(WorkflowEventKind.CREATED), publisher.workflowKinds());
    assertEquals("instance-1", publisher.workflows().getFirst().workflowId());
    assertEquals(List.of("instance-1#created"), publisher.workflowEventIds());

  }

  @Test
  @DisplayName("A task delivered again because it changed is reported as an update")
  public void aRedeliveredUserTaskIsAnUpdate() {

    observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of()));
    observer
        .userTaskDelivered(
            delivery("task-1", TaskInformation.ASSIGN, Map.of(PeaTaskMeta.ASSIGNEE, "james")));
    observer.userTaskDelivered(delivery("task-1", TaskInformation.UPDATE, Map.of()));

    assertEquals(
        List
            .of(
                UserTaskEventKind.CREATED, UserTaskEventKind.UPDATED, UserTaskEventKind.UPDATED),
        publisher.userTaskKinds());
    assertEquals(
        List.of("task-1#create", "task-1#assign", "task-1#update"), publisher.userTaskEventIds());

  }

  @Test
  @DisplayName("A task an engine reports without a reason is reported as a new one")
  public void aDeliveryWithoutAReasonIsANewTask() {

    observer.userTaskDelivered(delivery("task-1", null, Map.of()));

    assertEquals(List.of(UserTaskEventKind.CREATED), publisher.userTaskKinds());
    assertEquals(List.of("task-1#create"), publisher.userTaskEventIds());

  }

  private void aTerminatedUserTask(
      final String taskId,
      final String reason) {

    final var taskInformation = reason == null
        ? new TaskInformation(taskId, Map.of())
        : new TaskInformation(taskId, Map.of()).withReason(reason);
    observer
        .userTaskTerminated(
            new PeaUserTaskObservation(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, TestModels.USER_TASK_FORM, null, taskInformation, Map
                    .of()));

  }

  @Test
  @DisplayName("A task which is gone is reported as completed, and what it was stays readable")
  public void aTerminatedUserTaskIsReportedAsCompleted() {

    observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of()));
    publisher.userTasks().clear();
    publisher.userTaskKinds().clear();
    publisher.userTaskEventIds().clear();

    aTerminatedUserTask("task-1", TaskInformation.COMPLETE);

    assertEquals(List.of(UserTaskEventKind.COMPLETED), publisher.userTaskKinds());
    assertEquals(List.of("task-1#gone"), publisher.userTaskEventIds());
    assertEquals("task-1", publisher.userTasks().getFirst().userTaskId());
    assertTrue(
        deliveredUserTasks.of("task-1").isPresent(),
        "the report of its creation may still be waiting, and it is read from here");
    assertTrue(
        deliveredUserTasks
            .ofAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711")
            .isEmpty(),
        "a task which is gone is none of its business case's open tasks");

  }

  @Test
  @DisplayName("A task the engine says was withdrawn is reported as cancelled")
  public void aWithdrawnUserTaskIsReportedAsCancelled() {

    observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of()));
    publisher.userTaskKinds().clear();

    aTerminatedUserTask("task-1", TaskInformation.DELETE);

    assertEquals(List.of(UserTaskEventKind.CANCELED), publisher.userTaskKinds());

  }

  @Test
  @DisplayName("A task which is gone without a reason is reported as completed")
  public void aTerminationWithoutAReasonIsACompletion() {

    observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of()));
    publisher.userTaskKinds().clear();

    aTerminatedUserTask("task-1", null);

    assertEquals(List.of(UserTaskEventKind.COMPLETED), publisher.userTaskKinds());

  }

  @Test
  @DisplayName("The end of a task this node never saw is not reported")
  public void theEndOfAnUnknownTaskIsNotReported() {

    observer
        .userTaskTerminated(
            new PeaUserTaskObservation(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, TestModels.USER_TASK_FORM, null, new TaskInformation(
                    "task-of-another-node", Map.of()), Map.of()));

    assertTrue(publisher.userTasks().isEmpty());

  }

  @Test
  @DisplayName("A task of a process this application never deployed is passed over")
  public void aTaskOfAnUnknownProcessIsPassedOver() {

    observer
        .userTaskDelivered(
            new PeaUserTaskObservation(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, "AnotherProcess", TestModels.USER_TASK_FORM, "4711", new TaskInformation(
                    "task-1", Map.of()), Map.of()));

    assertTrue(publisher.userTasks().isEmpty());
    assertTrue(publisher.workflows().isEmpty());

  }

  @Test
  @DisplayName("A delivery which names no workflow aggregate is passed over")
  public void aDeliveryWithoutAnAggregateIsPassedOver() {

    observer
        .userTaskDelivered(
            new PeaUserTaskObservation(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, TestModels.USER_TASK_FORM, null, new TaskInformation(
                    "task-1", Map.of()), Map.of()));

    assertTrue(publisher.userTasks().isEmpty());

  }

  @Test
  @DisplayName("A delivery whose BPMN process the adapter could not tell is passed over")
  public void aDeliveryWithoutABpmnProcessIsPassedOver() {

    observer
        .userTaskDelivered(
            new PeaUserTaskObservation(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, null, TestModels.USER_TASK_FORM, "4711", new TaskInformation(
                    "task-1", Map.of()), Map.of()));

    assertTrue(publisher.userTasks().isEmpty());
    assertTrue(publisher.workflows().isEmpty());

  }

  @Test
  @DisplayName("The end of a task is reported although the termination names no BPMN process")
  public void aTerminationWithoutABpmnProcessIsReported() {

    observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of()));
    publisher.userTaskKinds().clear();

    observer
        .userTaskTerminated(
            new PeaUserTaskObservation(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, null, TestModels.USER_TASK_FORM, null, new TaskInformation(
                    "task-1", Map.of()).withReason(TaskInformation.COMPLETE), Map.of()));

    assertEquals(List.of(UserTaskEventKind.COMPLETED), publisher.userTaskKinds());
    assertEquals(
        TestModels.BPMN_PROCESS_ID,
        publisher.userTasks().getFirst().bpmnProcessId(),
        "what the terminated task was is what its delivery said");

  }

  @Test
  @DisplayName("The name of a user task is the one VanillaBP read off the deployed model")
  public void theTaskNameComesFromTheRegistry() {

    observer = observerOf(
        TestModels
            .deployed(
                new TestExtensionHandlers(
                    Map.of(TestModels.USER_TASK_ELEMENT, "Approve it, please"))));

    observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of()));

    assertEquals(
        "Approve it, please",
        deliveredUserTasks.of("task-1").orElseThrow().details().bpmnTaskName());

  }

}
