package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.cockpit.pea.PeaCockpitBridge;
import io.vanillabp.cockpit.pea.PeaCockpitObserver;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks;
import io.vanillabp.cockpit.pea.PeaUserTaskObservation;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaAdapter;

/**
 * What the cockpit reads back about a task or a business case, and what this BPMS has to answer
 * with: the deliveries this node was given. The interesting answers are the empty ones - a task
 * nobody delivered here, a business case whose tasks all ended - because they are what an
 * application notices as a cockpit which stopped following.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaCockpitBridgeTest {

  private PeaDeliveredUserTasks deliveredUserTasks;

  private PeaCockpitObserver observer;

  private PeaCockpitBridge bridge;

  @BeforeEach
  public void anApplicationWithOneDeployedProcess() {

    final var models = TestModels.deployed();
    deliveredUserTasks = new PeaDeliveredUserTasks(10);
    observer = new PeaCockpitObserver(
        models, deliveredUserTasks, new NameClashAvoidanceService(
            TestModels.configuration(NameClashAvoidance.NONE)), (
                adapterId,
                workflowModuleId,
                bpmnProcessId) -> "deployment-7", RecordingPublisher::new);
    bridge = new PeaCockpitBridge(
        TestModels.ADAPTER_ID, models, deliveredUserTasks, (
            adapterId,
            workflowModuleId,
            bpmnProcessId) -> "deployment-7", 10);

  }

  private void aDeliveredUserTask(
      final String taskId) {

    observer
        .userTaskDelivered(
            new PeaUserTaskObservation(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, TestModels.USER_TASK_FORM, "4711", new TaskInformation(
                    taskId, Map.of(CommonRestrictions.PROCESS_INSTANCE_ID, "instance-1")), Map.of()));

  }

  private static UserTaskReference reference(
      final String taskId) {

    return new UserTaskReference(
        TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711", "instance-1", taskId, TestModels.USER_TASK_FORM, TestModels.USER_TASK_ELEMENT);

  }

  @Test
  @DisplayName("The bridge names the adapter it serves and its BPMS")
  public void theBridgeNamesWhatItServes() {

    assertEquals(TestModels.ADAPTER_ID, bridge.adapterId());
    assertEquals(PeaAdapter.ADAPTER_TYPE, bridge.adapterType());

  }

  @Test
  @DisplayName("What a delivery said is what the dispatch which follows it reads")
  public void aDeliveredTaskIsReadBack() {

    aDeliveredUserTask("task-1");

    final var details = bridge.prefilledUserTaskDetails(reference("task-1"));
    assertTrue(details.isPresent());
    assertEquals("Approve the ride", details.get().bpmnTaskName());
    assertEquals("A taxi ride", details.get().bpmnProcessName());

  }

  @Test
  @DisplayName("A task this node never saw is answered with nothing, which drops the report")
  public void anUnknownTaskIsAnsweredWithNothing() {

    assertTrue(bridge.prefilledUserTaskDetails(reference("task-of-another-node")).isEmpty());

  }

  @Test
  @DisplayName("A workflow is answered with what this application deployed")
  public void aWorkflowIsAnsweredFromWhatWasDeployed() {

    final var details = bridge
        .prefilledWorkflowDetails(
            new WorkflowReference(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711", "instance-1"));

    assertTrue(details.isPresent());
    assertEquals("A taxi ride", details.get().bpmnProcessName());
    assertEquals("deployment-7", details.get().bpmnProcessVersion());
    assertEquals("4711", details.get().businessId());

  }

  @Test
  @DisplayName("A process this application never deployed is answered with nothing")
  public void anUnknownProcessIsAnsweredWithNothing() {

    assertTrue(
        bridge
            .prefilledWorkflowDetails(
                new WorkflowReference(
                    TestModels.ADAPTER_ID, TestModels.MODULE_ID, "AnotherProcess", "4711", "instance-1"))
            .isEmpty());

  }

  @Test
  @DisplayName("The workflows and the user tasks of a business case are the deliveries this node holds")
  public void theTasksOfAnAggregateAreTheOnesThisNodeHolds() {

    aDeliveredUserTask("task-1");
    aDeliveredUserTask("task-2");

    assertEquals(
        List.of("instance-1"),
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711")
            .stream()
            .map(WorkflowReference::workflowId)
            .toList());
    assertEquals(
        List.of("task-1", "task-2"),
        bridge
            .userTasksOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711", List.of())
            .stream()
            .map(UserTaskReference::userTaskId)
            .toList());
    assertEquals(
        List.of("task-2"),
        bridge
            .userTasksOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711", List.of("task-2"))
            .stream()
            .map(UserTaskReference::userTaskId)
            .toList());
    assertEquals(
        "task-1",
        bridge
            .userTaskOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711", "task-1")
            .orElseThrow()
            .userTaskId());

  }

  @Test
  @DisplayName("A task which ended is still read by the report which was waiting for it")
  public void anEndedTaskIsStillReadByItsPendingReport() {

    aDeliveredUserTask("task-1");
    deliveredUserTasks.ended("task-1");

    assertTrue(
        bridge.prefilledUserTaskDetails(reference("task-1")).isPresent(),
        "the report of its creation is dispatched after the task ended, and this BPMS cannot be asked what it was");
    assertTrue(
        bridge
            .userTaskOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711", "task-1")
            .isEmpty(),
        "a task which is gone is not one an application can read any more");
    assertTrue(
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711")
            .isEmpty(),
        "the case has no open task left, so this node knows no workflow of it");

  }

  @Test
  @DisplayName("The deliveries of another configured engine are not answered as this one's")
  public void theDeliveriesOfAnotherAdapterAreNotAnswered() {

    aDeliveredUserTask("task-1");

    final var anotherEngine = new PeaCockpitBridge(
        "another-pea", TestModels.deployed(), deliveredUserTasks, (
            adapterId,
            workflowModuleId,
            bpmnProcessId) -> "deployment-7", 10);

    assertTrue(
        anotherEngine
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711")
            .isEmpty());
    assertTrue(
        anotherEngine
            .prefilledWorkflowDetails(
                new WorkflowReference(
                    TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711", "instance-1"))
            .isEmpty(),
        "a workflow of another engine is not answered with what this one deployed");
    assertTrue(
        anotherEngine.prefilledUserTaskDetails(reference("task-1")).isEmpty());
    assertTrue(
        anotherEngine
            .userTasksOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711", List.of())
            .isEmpty());
    assertTrue(
        anotherEngine
            .userTaskOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711", "task-1")
            .isEmpty());

  }

  @Test
  @DisplayName("A task of another business case is not answered, whoever asks for it")
  public void aTaskOfAnotherAggregateIsNotAnswered() {

    aDeliveredUserTask("task-1");

    assertTrue(
        bridge
            .userTaskOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "0815", "task-1")
            .isEmpty());

  }

  @Test
  @DisplayName("A business case this node knows nothing about is said out loud once, whichever read asks")
  public void anUnknownAggregateIsSaidOutLoudOnce(
      final CapturedOutput output) {

    bridge.workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "0815");
    assertTrue(
        bridge
            .userTasksOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "0815", List.of())
            .isEmpty(),
        "the user tasks come from the same memory, so they are unknown as well");

    final var said = output.getAll();
    final var aboutThisCase = said
        .lines()
        .filter(line -> line.contains("was not reported to the Business Cockpit"))
        .filter(line -> line.contains("'0815'"))
        .count();

    assertEquals(
        1,
        aboutThisCase,
        () -> "both reads say the same sentence, and they say it once per business case: "
            + said);

  }

}
