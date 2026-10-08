package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
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
import io.vanillabp.integration.extension.spi.election.WorkflowStart;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.wiring.PeaTaskMeta;

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

  /**
   * What the engine calls the running instance. The records of most tests here do not carry it,
   * like the record of an engine which names no process instance.
   */
  private static final String WORKFLOW_ID = "instance-1";

  /**
   * Words of the sentence which says that the change of a case was not reported, because its open
   * user task is known only from the delivery log and nothing names its version.
   */
  private static final String VERSION_IS_UNKNOWN = "was not reported to the Business Cockpit for its workflow";

  private PeaDeployedProcessesRegistry deployedProcesses;

  private PeaDeliveredUserTasks deliveredUserTasks;

  private TestDeliveryLog deliveryLog;

  /** What VanillaBP wrote down about starts. It survives a restart, like the delivery log. */
  private final TestElection election = new TestElection();

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
        deployedProcesses, TestModels.claimingTheRide(), deliveredUserTasks, (
            adapterId,
            workflowModuleId,
            bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, RecordingPublisher::new);
    bridge = new PeaCockpitBridge(
        TestModels.ADAPTER_ID, deployedProcesses, deliveredUserTasks, recordedUserTasks, (
            adapterId,
            workflowModuleId,
            bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, election, 10);

  }

  /**
   * A user task the engine delivered to this node, which is what fills the memory. This engine
   * names the running instance, so the memory knows a workflow id the records of these tests
   * mostly do not carry.
   */
  private void aDeliveredUserTask(
      final String taskId) {

    aDeliveredUserTask(taskId, null);

  }

  /**
   * A user task the engine delivered to this node, with the version tag the engine wrote into
   * its meta map, or without one.
   */
  private void aDeliveredUserTask(
      final String taskId,
      final String versionTag) {

    final var meta = new LinkedHashMap<String, String>();
    meta.put(CommonRestrictions.PROCESS_INSTANCE_ID, WORKFLOW_ID);
    if (versionTag != null) {
      meta.put(PeaTaskMeta.PROCESS_VERSION_TAG, versionTag);
    }
    observer
        .userTaskDelivered(
            new PeaUserTaskObservation(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, TestModels.USER_TASK_FORM, AGGREGATE_ID, new TaskInformation(
                    taskId, meta), Map.of()));

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
        .anOpenTaskOfVersion(
            TestModels.ADAPTER_ID, AGGREGATE_ID, "task-1", TestModels.VERSION_TAG, Instant.now());

    assertEquals(List.of("task-1"), openUserTaskIds());
    assertEquals(
        List.of(AGGREGATE_ID),
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID)
            .stream()
            .map(WorkflowReference::workflowId)
            .toList(),
        "the record names no workflow, so the case is shown under its aggregate");

    final var one = bridge
        .userTaskOfAggregate(
            TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, "task-1")
        .orElseThrow();
    assertEquals(TestModels.USER_TASK_FORM, one.taskDefinition());
    assertEquals(
        TestModels.USER_TASK_ELEMENT,
        one.bpmnTaskId(),
        "the record names no BPMN element, so it comes from what the adapter deployed");

  }

  @Test
  @DisplayName("A record which names the workflow and the BPMN element is read as it was written")
  public void aRecordNamingItsWorkflowIsShownUnderThatWorkflow() {

    deliveryLog
        .anOpenTaskNaming(
            TestModels.ADAPTER_ID, AGGREGATE_ID, "task-1", WORKFLOW_ID, TestModels.USER_TASK_ELEMENT,
            TestModels.VERSION_TAG, Instant
                .now());

    assertEquals(
        List.of(WORKFLOW_ID),
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID)
            .stream()
            .map(WorkflowReference::workflowId)
            .toList(),
        "the adapter wrote the engine's id of the workflow into the record, and the case is shown under it");
    final var one = bridge
        .userTaskOfAggregate(
            TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, "task-1")
        .orElseThrow();
    assertEquals(WORKFLOW_ID, one.workflowId());
    assertEquals(TestModels.USER_TASK_ELEMENT, one.bpmnTaskId());
    assertEquals(TestModels.USER_TASK_FORM, one.taskDefinition());

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
  @DisplayName("A task read out of the log names the version its record names, and so does its business case")
  public void aTaskOfTheLogNamesTheVersionOfItsRecord(
      final CapturedOutput output) {

    deliveryLog
        .anOpenTaskOfVersion(
            TestModels.ADAPTER_ID, AGGREGATE_ID, "task-1", TestModels.VERSION_TAG, Instant.now());

    assertEquals(
        TestModels.VERSION_TAG,
        bridge
            .userTaskOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID, "task-1")
            .orElseThrow()
            .processVersion(),
        "VanillaBP wrote the version tag of the delivery into the record");
    final var found = bridge
        .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID);
    assertEquals(List.of(TestModels.VERSION_TAG), found.stream().map(WorkflowReference::processVersion).toList());
    assertEquals(
        TestModels.VERSION_TAG,
        bridge.prefilledWorkflowDetails(found.getFirst()).orElseThrow().bpmnProcessVersion(),
        "the case shows the version that picks its details provider, not what this release deployed");
    assertFalse(
        output
            .getAll()
            .lines()
            .anyMatch(line -> line.contains(VERSION_IS_UNKNOWN) && line.contains("'%s'".formatted(AGGREGATE_ID))),
        output.getAll());

  }

  @Test
  @DisplayName("A business case known only from a record without a version is not reported, and the log says why")
  public void aTaskOfTheLogWithoutAVersionIsNotReported(
      final CapturedOutput output) {

    // a case of its own, because the output captured here is the one of the whole class
    deliveryLog
        .anOpenTask(TestModels.ADAPTER_ID, "4712", "task-2", Instant.now());

    assertNull(
        bridge
            .userTaskOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4712", "task-2")
            .orElseThrow()
            .processVersion());
    assertTrue(
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4712")
            .isEmpty(),
        "a report without a version passes over every details provider which names one, and the cockpit would show empty details");
    bridge.workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4712");
    assertEquals(
        1,
        output
            .getAll()
            .lines()
            .filter(line -> line.contains(VERSION_IS_UNKNOWN) && line.contains("'4712'"))
            .count(),
        () -> "the sentence is said once per business case: "
            + output.getAll());

  }

  @Test
  @DisplayName("A record without a version takes the version of VanillaBP's note of the start")
  public void aTaskOfTheLogWithoutAVersionTakesTheOneOfTheStart() {

    // the record names no workflow, so the task stands under the aggregate's id, and the note
    // names the engine's id of the one workflow VanillaBP started for the case
    deliveryLog
        .anOpenTask(TestModels.ADAPTER_ID, AGGREGATE_ID, "task-1", Instant.now());
    election.started(AGGREGATE_ID, new WorkflowStart(TestModels.ADAPTER_ID, WORKFLOW_ID, TestModels.VERSION_TAG, true));

    final var found = bridge
        .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID);

    assertEquals(
        List.of(AGGREGATE_ID),
        found.stream().map(WorkflowReference::workflowId).toList(),
        "the task names the id the cockpit shows the case under, so it stays");
    assertEquals(TestModels.VERSION_TAG, found.getFirst().processVersion());

  }

  @Test
  @DisplayName("Where the memory knows the version tag of the workflow, the memory answers before the record")
  public void theMemoryNamesTheVersionBeforeTheRecord() {

    aDeliveredUserTask("task-1", TestModels.VERSION_TAG);
    deliveredUserTasks.ended("task-1");
    deliveryLog
        .anOpenTaskNaming(
            TestModels.ADAPTER_ID, AGGREGATE_ID, "task-2", WORKFLOW_ID, null, "a-tag-of-the-record", Instant
                .now());

    assertEquals(
        List.of(TestModels.VERSION_TAG),
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID)
            .stream()
            .map(WorkflowReference::processVersion)
            .toList(),
        "the memory heard what the engine said, and it answers first wherever it can");

  }

  @Test
  @DisplayName("A task this node was delivered without a version tag is still reported under none")
  public void aDeliveredTaskWithoutAVersionIsStillReported(
      final CapturedOutput output) {

    aDeliveredUserTask("task-1");
    deliveryLog
        .anOpenTask(TestModels.ADAPTER_ID, AGGREGATE_ID, "task-2", Instant.now());

    final var found = bridge
        .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, AGGREGATE_ID);

    assertEquals(List.of(WORKFLOW_ID), found.stream().map(WorkflowReference::workflowId).toList());
    assertNull(
        found.getFirst().processVersion(),
        "the report of the delivery named no version either, so an update under none changes nothing");
    assertFalse(
        output
            .getAll()
            .lines()
            .anyMatch(line -> line.contains(VERSION_IS_UNKNOWN) && line.contains("'%s'".formatted(AGGREGATE_ID))),
        output.getAll());

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
