package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
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
import io.vanillabp.cockpit.pea.PeaRecordedUserTasks;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import io.vanillabp.pea.observation.PeaUserTaskObservation;

/**
 * What the cockpit reads back once the memory of this node cannot answer any more.
 * <p>
 * A node which restarts starts with an empty memory, and a node which never got the delivery
 * never had one. VanillaBP wrote the delivery down in the application's own database, so that
 * record answers where the memory cannot. A restart is simulated the way it looks from here: the
 * same delivery log, and a memory which holds nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaBridgeReadsTheDeliveryLogTest {

  private static final String AGGREGATE_ID = "4711";

  /** What the engine calls the running instance, which a record of a delivery never carries. */
  private static final String WORKFLOW_ID = "instance-1";

  private PeaDeployedProcessesRegistry deployedProcesses;

  private PeaDeliveredUserTasks deliveredUserTasks;

  private TestDeliveryLog deliveryLog;

  private PeaCockpitObserver observer;

  private PeaCockpitBridge bridge;

  @BeforeEach
  public void anApplicationWithOneDeployedProcess() {

    deployedProcesses = TestModels.deployed();
    deliveryLog = new TestDeliveryLog();
    startTheNode();

  }

  /**
   * Builds what a boot builds: a memory holding nothing, an observer writing into it, and a
   * bridge reading it next to the delivery log the application's database keeps.
   */
  private void startTheNode() {

    startTheNodeReading(TestModels.recorded(deployedProcesses, deliveryLog));

  }

  private void startTheNodeReading(
      final PeaRecordedUserTasks recordedUserTasks) {

    deliveredUserTasks = new PeaDeliveredUserTasks(10);
    observer = new PeaCockpitObserver(
        deployedProcesses, deliveredUserTasks, (
            adapterId,
            workflowModuleId,
            bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, RecordingPublisher::new);
    bridge = new PeaCockpitBridge(
        TestModels.ADAPTER_ID, deployedProcesses, deliveredUserTasks, recordedUserTasks, (
            adapterId,
            workflowModuleId,
            bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, 10);

  }

  /**
   * A user task the engine delivered to this node, which is what fills the memory. This engine
   * names the running instance, so the memory knows a workflow id a delivery record never
   * carries.
   */
  private void aDeliveredUserTask(
      final String taskId) {

    observer
        .userTaskDelivered(
            new PeaUserTaskObservation(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, TestModels.USER_TASK_FORM, AGGREGATE_ID, new TaskInformation(
                    taskId, Map.of(CommonRestrictions.PROCESS_INSTANCE_ID, WORKFLOW_ID)), Map
                        .of()));

  }

  private List<String> openUserTaskIds() {

    return bridge
        .userTasksOfAggregate(
            TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, List.of())
        .stream()
        .map(UserTaskReference::userTaskId)
        .toList();

  }

  @Test
  @DisplayName("After a restart the log says which user tasks this application reported")
  public void aTaskOfAnEmptyMemoryIsReadFromTheLog() {

    deliveryLog
        .anOpenTask(TestModels.ADAPTER_ID, AGGREGATE_ID, "task-1", Instant.now());

    assertEquals(List.of("task-1"), openUserTaskIds());
    assertEquals(
        List.of(AGGREGATE_ID),
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID)
            .stream()
            .map(WorkflowReference::workflowId)
            .toList(),
        "the record names no workflow on this BPMS, so the case is shown under its aggregate");

    final var one = bridge
        .userTaskOfAggregate(
            TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, "task-1")
        .orElseThrow();
    assertEquals(TestModels.USER_TASK_FORM, one.taskDefinition());
    assertEquals(
        TestModels.USER_TASK_ELEMENT,
        one.bpmnTaskId(),
        "the record names no BPMN element on this BPMS, so it comes from what the adapter deployed");

  }

  @Test
  @DisplayName("What a task shows stays out of the log, so a fresh node shows nothing")
  public void theDetailsOfATaskAreNotInTheLog() {

    deliveryLog
        .anOpenTask(TestModels.ADAPTER_ID, AGGREGATE_ID, "task-1", Instant.now());

    assertTrue(
        bridge
            .prefilledUserTaskDetails(
                bridge
                    .userTaskOfAggregate(
                        TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, "task-1")
                    .orElseThrow())
            .isEmpty(),
        "a record carries identifiers and an outcome, and not one word the engine said");

  }

  @Test
  @DisplayName("A task read out of the log names no version, so a provider naming one does not run for it")
  public void aTaskOfTheLogNamesNoVersion() {

    deliveryLog
        .anOpenTask(TestModels.ADAPTER_ID, AGGREGATE_ID, "task-1", Instant.now());

    assertNull(
        bridge
            .userTaskOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, "task-1")
            .orElseThrow()
            .processVersion(),
        "the platform writes the same fields for every BPMS, and a version of a process is not among them");

  }

  @Test
  @DisplayName("A task the application has completed is not read back as open")
  public void aClosedRecordIsNotOpen(
      final CapturedOutput output) {

    deliveryLog.aClosedTask(TestModels.ADAPTER_ID, AGGREGATE_ID, "task-1");

    assertEquals(List.of(), openUserTaskIds());
    assertTrue(
        bridge
            .userTaskOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, "task-1")
            .isEmpty());
    assertTrue(
        output.getAll().contains("was not reported to the Business Cockpit"),
        "neither source knows an open task of the case, and that is said out loud");

  }

  @Test
  @DisplayName("A record of another configured engine is not answered as this one's")
  public void aRecordOfAnotherAdapterIsNotAnswered() {

    deliveryLog.anOpenTask("another-pea", AGGREGATE_ID, "task-1", Instant.now());

    assertEquals(List.of(), openUserTaskIds());
    assertTrue(
        bridge
            .userTaskOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, "task-1")
            .isEmpty(),
        "answering it would send the cockpit to the wrong BPMS");

  }

  @Test
  @DisplayName("Where both sources know a task, the memory answers, because it carries more")
  public void theMemoryAnswersWhereBothKnowTheTask() {

    aDeliveredUserTask("task-1");
    deliveryLog
        .anOpenTask(TestModels.ADAPTER_ID, AGGREGATE_ID, "task-1", Instant.now());

    assertEquals(List.of("task-1"), openUserTaskIds(), "one task, not two");
    assertEquals(
        List.of(WORKFLOW_ID),
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID)
            .stream()
            .map(WorkflowReference::workflowId)
            .toList(),
        "the memory knows the workflow the engine named, and the record does not");
    assertTrue(
        bridge
            .prefilledUserTaskDetails(
                bridge
                    .userTaskOfAggregate(
                        TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, "task-1")
                    .orElseThrow())
            .isPresent());

  }

  @Test
  @DisplayName("A task the engine took away stays over, although its record is still open")
  public void aTaskTheEngineTookAwayStaysOver() {

    aDeliveredUserTask("task-1");
    deliveryLog
        .anOpenTask(TestModels.ADAPTER_ID, AGGREGATE_ID, "task-1", Instant.now());
    deliveredUserTasks.ended("task-1");

    assertEquals(
        List.of(),
        openUserTaskIds(),
        "the record is stamped when the application completes a task, and nobody did");
    assertTrue(
        bridge
            .userTaskOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, "task-1")
            .isEmpty());

  }

  @Test
  @DisplayName("Both sources are read, so a restart between two tasks loses neither")
  public void bothSourcesAreRead() {

    deliveryLog
        .anOpenTask(TestModels.ADAPTER_ID, AGGREGATE_ID, "task-before", Instant.now());
    aDeliveredUserTask("task-after");

    assertEquals(List.of("task-after", "task-before"), openUserTaskIds());
    assertEquals(
        List.of("task-before"),
        bridge
            .userTasksOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, List
                    .of("task-before"))
            .stream()
            .map(UserTaskReference::userTaskId)
            .toList(),
        "the cockpit may ask for one of them, and that filter runs over both sources");

  }

  @Test
  @DisplayName("A business case whose tasks come from both sources is still one case")
  public void aCaseFedByBothSourcesAppearsOnce() {

    aDeliveredUserTask("task-known-here");
    deliveryLog
        .anOpenTask(TestModels.ADAPTER_ID, AGGREGATE_ID, "task-of-another-node", Instant.now());

    assertEquals(
        List.of(WORKFLOW_ID),
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID)
            .stream()
            .map(WorkflowReference::workflowId)
            .toList(),
        "the record names no workflow, and the aggregate's id next to the engine's own id would be one case twice");
    assertEquals(
        List.of(WORKFLOW_ID, WORKFLOW_ID),
        bridge
            .userTasksOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, List.of())
            .stream()
            .map(UserTaskReference::workflowId)
            .toList(),
        "both tasks are shown under the same workflow");

  }

  @Test
  @DisplayName("An application which configured no delivery log is answered by the memory alone")
  public void anApplicationWithoutADeliveryLogIsAnswered() {

    startTheNodeReading(TestModels.recorded(deployedProcesses, null));
    aDeliveredUserTask("task-1");

    assertEquals(List.of("task-1"), openUserTaskIds(), "no store is no failure");
    assertTrue(
        bridge
            .userTaskOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, "task-of-another-node")
            .isEmpty());

  }

  @Test
  @DisplayName("A BPMN process no workflow service declares has no aggregate, and so no log")
  public void aProcessWithoutAWorkflowServiceIsAnswered() {

    startTheNodeReading(
        new PeaRecordedUserTasks(
            TestModels.handlersServing(null), TestModels.resolvingTo(deliveryLog), deployedProcesses));
    deliveryLog
        .anOpenTask(TestModels.ADAPTER_ID, AGGREGATE_ID, "task-1", Instant.now());

    assertEquals(
        List.of(),
        openUserTaskIds(),
        "which store holds the records follows the aggregate, and there is none");

  }

}
