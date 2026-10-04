package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.cockpit.pea.PeaCockpitBridge;
import io.vanillabp.cockpit.pea.PeaCockpitObserver;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks;
import io.vanillabp.integration.extension.spi.election.WorkflowStart;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.wiring.PeaTaskMeta;

/**
 * What the cockpit reads back about a task or a business case. All this BPMS has to answer with
 * are the deliveries this node was given. The interesting answers are the empty ones: a task
 * nobody delivered here, or a business case whose tasks all ended. Those are what an application
 * notices as a cockpit which stopped following.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaCockpitBridgeTest {

  /** The words of the warning about a workflow VanillaBP started whose version is unknown. */
  private static final String VERSION_IS_UNKNOWN = "is unknown. VanillaBP started that workflow";

  /** The words of the warning about a business case no source knows anything about. */
  private static final String NOTHING_IS_KNOWN = "because no source knows a workflow";

  private PeaDeliveredUserTasks deliveredUserTasks;

  private TestDeliveryLog deliveryLog;

  /** What VanillaBP wrote down about starts. It survives a restart, like the delivery log. */
  private final TestElection election = new TestElection();

  private PeaCockpitObserver observer;

  private PeaCockpitBridge bridge;

  @BeforeEach
  public void anApplicationWithOneDeployedProcess() {

    final var deployedProcesses = TestModels.deployed();
    deliveredUserTasks = new PeaDeliveredUserTasks(10);
    deliveryLog = new TestDeliveryLog();
    observer = new PeaCockpitObserver(
        deployedProcesses, deliveredUserTasks, (
            adapterId,
            workflowModuleId,
            bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, RecordingPublisher::new);
    bridge = new PeaCockpitBridge(
        TestModels.ADAPTER_ID, deployedProcesses, deliveredUserTasks, TestModels
            .recorded(deployedProcesses, deliveryLog), (
                adapterId,
                workflowModuleId,
                bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, election, 10);

  }

  private void aDeliveredUserTask(
      final String taskId) {

    aDeliveredUserTask(taskId, null);

  }

  private void aDeliveredUserTask(
      final String taskId,
      final String versionTag) {

    aDeliveredUserTask(observer, taskId, "4711", "instance-1", versionTag);

  }

  private static void aDeliveredUserTask(
      final PeaCockpitObserver observer,
      final String taskId,
      final String workflowAggregateId,
      final String workflowId,
      final String versionTag) {

    final var meta = new LinkedHashMap<String, String>();
    meta.put(CommonRestrictions.PROCESS_INSTANCE_ID, workflowId);
    if (versionTag != null) {
      meta.put(PeaTaskMeta.PROCESS_VERSION_TAG, versionTag);
    }
    observer
        .userTaskDelivered(
            new PeaUserTaskObservation(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, TestModels.USER_TASK_FORM, workflowAggregateId, new TaskInformation(
                    taskId, meta), Map.of()));

  }

  private static WorkflowReference workflow(
      final String workflowAggregateId,
      final String workflowId) {

    return new WorkflowReference(
        TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, null, workflowAggregateId, workflowId);

  }

  private static UserTaskReference reference(
      final String taskId) {

    return new UserTaskReference(
        TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, null, "4711", "instance-1", taskId, TestModels.USER_TASK_FORM, TestModels.USER_TASK_ELEMENT);

  }

  @Test
  @DisplayName("The bridge names the adapter it serves and its BPMS")
  public void theBridgeNamesWhatItServes() {

    assertEquals(TestModels.ADAPTER_ID, bridge.adapterId());
    assertEquals(PeaAdapter.ADAPTER_TYPE, bridge.adapterType());

  }

  @Test
  @DisplayName("What a delivery said is what the report built from it reads")
  public void aDeliveredTaskIsReadBack() {

    aDeliveredUserTask("task-1");

    final var details = bridge.prefilledUserTaskDetails(reference("task-1"));
    assertTrue(details.isPresent());
    assertEquals(TestModels.USER_TASK_NAME, details.get().bpmnTaskName());
    assertEquals(TestModels.PROCESS_NAME, details.get().bpmnProcessName());

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
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, null, "4711", "instance-1"));

    assertTrue(details.isPresent());
    assertEquals(TestModels.PROCESS_NAME, details.get().bpmnProcessName());
    assertEquals(TestModels.DEPLOYMENT_KEY, details.get().bpmnProcessVersion());
    assertEquals("4711", details.get().businessId());

  }

  @Test
  @DisplayName("A process this application never deployed is answered with nothing")
  public void anUnknownProcessIsAnsweredWithNothing() {

    assertTrue(
        bridge
            .prefilledWorkflowDetails(
                new WorkflowReference(
                    TestModels.ADAPTER_ID, TestModels.MODULE_ID, "AnotherProcess", null, "4711", "instance-1"))
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
  @DisplayName("A workflow is answered under the version of the delivery it was found through")
  public void aWorkflowCarriesTheVersionOfTheDeliveryItWasFoundThrough() {

    aDeliveredUserTask("task-1", TestModels.VERSION_TAG);

    assertEquals(
        List.of(TestModels.VERSION_TAG),
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711")
            .stream()
            .map(WorkflowReference::processVersion)
            .toList());
    assertEquals(
        List.of(TestModels.VERSION_TAG),
        bridge
            .userTasksOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711", List.of())
            .stream()
            .map(UserTaskReference::processVersion)
            .toList());

  }

  @Test
  @DisplayName("A business case shows the version its user tasks show")
  public void aCaseShowsTheVersionItsUserTasksShow() {

    aDeliveredUserTask("task-1", TestModels.VERSION_TAG);

    assertEquals(
        TestModels.VERSION_TAG,
        bridge.prefilledUserTaskDetails(reference("task-1")).orElseThrow().bpmnProcessVersion());
    assertEquals(
        TestModels.VERSION_TAG,
        bridge
            .prefilledWorkflowDetails(workflow("4711", "instance-1"))
            .orElseThrow()
            .bpmnProcessVersion(),
        "the engine named a version with the task, so the case names the same one");

  }

  @Test
  @DisplayName("A business case whose delivery named no version shows what this application deployed")
  public void aCaseWithoutAVersionTagShowsTheDeploymentKey() {

    aDeliveredUserTask("task-1");

    assertEquals(
        TestModels.DEPLOYMENT_KEY,
        bridge.prefilledUserTaskDetails(reference("task-1")).orElseThrow().bpmnProcessVersion());
    assertEquals(
        TestModels.DEPLOYMENT_KEY,
        bridge
            .prefilledWorkflowDetails(workflow("4711", "instance-1"))
            .orElseThrow()
            .bpmnProcessVersion(),
        "this engine fills no version tag, so both read what this release deployed");

  }

  @Test
  @DisplayName("A business case whose user tasks all ended keeps its version")
  public void aCaseWhoseTasksEndedKeepsItsVersion() {

    aDeliveredUserTask("task-1", TestModels.VERSION_TAG);
    deliveredUserTasks.ended("task-1");

    assertEquals(
        TestModels.VERSION_TAG,
        bridge
            .prefilledWorkflowDetails(workflow("4711", "instance-1"))
            .orElseThrow()
            .bpmnProcessVersion(),
        "what a case runs on does not change when its last task is finished");

  }

  @Test
  @DisplayName("A business case this node holds no delivery of shows what this application deployed")
  public void aForgottenCaseShowsTheDeploymentKey() {

    final var deployedProcesses = TestModels.deployed();
    final var oneDeliveryAtATime = new PeaDeliveredUserTasks(1);
    final var narrowObserver = new PeaCockpitObserver(
        deployedProcesses, oneDeliveryAtATime, (
            adapterId,
            workflowModuleId,
            bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, RecordingPublisher::new);
    final var narrowBridge = new PeaCockpitBridge(
        TestModels.ADAPTER_ID, deployedProcesses, oneDeliveryAtATime, TestModels
            .recorded(deployedProcesses, deliveryLog), (
                adapterId,
                workflowModuleId,
                bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, election, 10);

    aDeliveredUserTask(narrowObserver, "task-1", "4711", "instance-1", TestModels.VERSION_TAG);
    aDeliveredUserTask(narrowObserver, "task-2", "4712", "instance-2", TestModels.VERSION_TAG);

    assertEquals(
        TestModels.DEPLOYMENT_KEY,
        narrowBridge
            .prefilledWorkflowDetails(workflow("4711", "instance-1"))
            .orElseThrow()
            .bpmnProcessVersion(),
        "the delivery which carried the tag was pushed out, and nothing else on this BPMS keeps it");
    assertEquals(
        TestModels.VERSION_TAG,
        narrowBridge
            .prefilledWorkflowDetails(workflow("4712", "instance-2"))
            .orElseThrow()
            .bpmnProcessVersion(),
        "the case the node still holds a delivery of answers with the tag");

  }

  @Test
  @DisplayName("A task which ended is still what the memory says it was")
  public void anEndedTaskIsStillReadBack() {

    aDeliveredUserTask("task-1");
    deliveredUserTasks.ended("task-1");

    assertTrue(
        bridge.prefilledUserTaskDetails(reference("task-1")).isPresent(),
        "this BPMS cannot be asked what a task was, so the memory keeps saying it");
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

    final var deployedProcesses = TestModels.deployed();
    final var anotherEngine = new PeaCockpitBridge(
        "another-pea", deployedProcesses, deliveredUserTasks, TestModels
            .recorded(deployedProcesses, deliveryLog), (
                adapterId,
                workflowModuleId,
                bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, election, 10);

    assertTrue(
        anotherEngine
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711")
            .isEmpty());
    assertTrue(
        anotherEngine
            .prefilledWorkflowDetails(
                new WorkflowReference(
                    TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, null, "4711", "instance-1"))
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
        .filter(line -> line.contains(NOTHING_IS_KNOWN))
        .filter(line -> line.contains("'0815'"))
        .count();

    assertEquals(
        1,
        aboutThisCase,
        () -> "both reads say the same sentence, and they say it once per business case: "
            + said);

  }

  @Test
  @DisplayName("A business case without an open user task is found by the workflow VanillaBP started")
  public void aCaseWithoutAnOpenTaskIsFoundByItsStart(
      final CapturedOutput output) {

    // a case of its own, because the output captured here is the one of the whole class
    aDeliveredUserTask(observer, "task-3", "4713", "instance-3", TestModels.VERSION_TAG);
    deliveredUserTasks.ended("task-3");
    election.started("4713", "instance-3");

    // what BusinessCockpitService.aggregateChanged asks while the workflow is busy with a
    // service task, after its only user task so far was finished
    final var found = bridge
        .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4713");

    assertEquals(List.of("instance-3"), found.stream().map(WorkflowReference::workflowId).toList());
    assertEquals(
        TestModels.VERSION_TAG,
        found.getFirst().processVersion(),
        "the node still remembers a delivery of that workflow, and the delivery named its version");
    final var prefill = bridge.prefilledWorkflowDetails(found.getFirst()).orElseThrow();
    assertEquals("4713", prefill.businessId());
    assertEquals(TestModels.PROCESS_NAME, prefill.bpmnProcessName());
    assertTrue(
        bridge
            .userTasksOfAggregate(
                TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4713", List.of())
            .isEmpty(),
        "the case has no open user task, which is no reason to warn while its workflow is known");
    assertFalse(
        output
            .getAll()
            .lines()
            .anyMatch(line -> line.contains("was not reported to the Business Cockpit") && line.contains("'4713'")),
        output.getAll());

  }

  @Test
  @DisplayName("A workflow VanillaBP started is not reported while nothing on this node says which version it runs on")
  public void aStartWithoutAKnownVersionIsNotReported(
      final CapturedOutput output) {

    election.started("4714", "instance-4");

    assertTrue(
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4714")
            .isEmpty(),
        "a report without a version passes over every details provider which names one, and the cockpit would show empty details");
    assertTrue(
        output
            .getAll()
            .lines()
            .anyMatch(line -> line.contains(VERSION_IS_UNKNOWN) && line.contains("'4714'")),
        output.getAll());
    assertFalse(
        output
            .getAll()
            .lines()
            .anyMatch(line -> line.contains(NOTHING_IS_KNOWN) && line.contains("'4714'")),
        "VanillaBP knows the workflow, so the sentence about a case nobody knows would be wrong");

  }

  @Test
  @DisplayName("Where an open user task names the workflow, the task answers and not the start")
  public void anOpenTaskNamesTheWorkflowBeforeTheStart() {

    aDeliveredUserTask("task-1");
    // an engine whose answer to a start differs from what its deliveries name. The cockpit shows
    // the case under the id of its first task, so that id has to stay
    election.started("4711", "instance-of-the-start");

    assertEquals(
        List.of("instance-1"),
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711")
            .stream()
            .map(WorkflowReference::workflowId)
            .toList());

  }

  @Test
  @DisplayName("A business case without an open user task is reported under the version VanillaBP wrote down")
  public void aStartWithItsVersionIsReportedUnderThatVersion(
      final CapturedOutput output) {

    // nothing on this node: no delivery, no memory. VanillaBP took the version from a delivery
    // row of that workflow, for example one another node processed
    election.started("4715", new WorkflowStart(TestModels.ADAPTER_ID, "instance-5", TestModels.VERSION_TAG, true));

    final var found = bridge
        .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4715");

    assertEquals(List.of("instance-5"), found.stream().map(WorkflowReference::workflowId).toList());
    assertEquals(TestModels.VERSION_TAG, found.getFirst().processVersion());
    final var prefill = bridge.prefilledWorkflowDetails(found.getFirst()).orElseThrow();
    assertEquals(
        TestModels.VERSION_TAG,
        prefill.bpmnProcessVersion(),
        "the case shows the version that picked its details provider, not what this release deployed");
    assertFalse(
        output
            .getAll()
            .lines()
            .anyMatch(line -> line.contains(VERSION_IS_UNKNOWN) && line.contains("'4715'")),
        output.getAll());

  }

  @Test
  @DisplayName("A start VanillaBP says no version will come for is not reported without one either")
  public void aStartWhoseVersionNeverComesIsNotReported(
      final CapturedOutput output) {

    // "never" is also the answer for a note without an adapter and for an adapter which said
    // nothing about versions, so it does not prove that no details provider names a version
    election.started("4716", new WorkflowStart(null, "instance-6", null, false));

    assertTrue(
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4716")
            .isEmpty());
    assertTrue(
        output
            .getAll()
            .lines()
            .anyMatch(line -> line.contains(VERSION_IS_UNKNOWN) && line.contains("'4716'")),
        output.getAll());

  }

  @Test
  @DisplayName("A start without a version is reported under the version a remembered delivery named")
  public void aStartWithoutAVersionTakesTheOneOfTheMemory() {

    aDeliveredUserTask(observer, "task-7", "4717", "instance-7", TestModels.VERSION_TAG);
    deliveredUserTasks.ended("task-7");
    election.started("4717", new WorkflowStart(TestModels.ADAPTER_ID, "instance-7", null, false));

    final var found = bridge
        .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4717");

    assertEquals(TestModels.VERSION_TAG, found.getFirst().processVersion());

  }

  @Test
  @DisplayName("A start without a version is not reported under the deployment key a remembered delivery shows")
  public void aStartWithoutAVersionIsNotReportedUnderTheDeploymentKey(
      final CapturedOutput output) {

    // the engine wrote no tag, so the memory shows what this application deployed. That picks no
    // details provider which names a version, so the case is not reported at all
    aDeliveredUserTask(observer, "task-10", "4720", "instance-10", null);
    deliveredUserTasks.ended("task-10");
    election.started("4720", new WorkflowStart(TestModels.ADAPTER_ID, "instance-10", null, true));

    final var found = bridge
        .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4720");

    assertEquals(List.of(), found.stream().map(WorkflowReference::processVersion).toList());
    assertTrue(
        output
            .getAll()
            .lines()
            .anyMatch(line -> line.contains(VERSION_IS_UNKNOWN) && line.contains("'4720'")),
        output.getAll());

  }

  @Test
  @DisplayName("A note of a start on another adapter is no workflow of this engine")
  public void aNoteOfAnotherAdapterIsLeftOut(
      final CapturedOutput output) {

    election.started("4718", new WorkflowStart("another-engine", "instance-8", TestModels.VERSION_TAG, true));

    assertTrue(
        bridge
            .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4718")
            .isEmpty());
    assertTrue(
        output
            .getAll()
            .lines()
            .anyMatch(line -> line.contains(NOTHING_IS_KNOWN) && line.contains("'4718'")),
        output.getAll());

  }

  @Test
  @DisplayName("A change after the end of a workflow is reported to the cockpit, and nothing reaches the engine")
  public void aChangeAfterTheEndIsReported() {

    // the only task ended, and so did the workflow. The note of the start lives longer, and since
    // the election reads it, aggregateChanged reaches this bridge after the end as well
    aDeliveredUserTask(observer, "task-9", "4719", "instance-9", TestModels.VERSION_TAG);
    deliveredUserTasks.ended("task-9");
    election.started("4719", new WorkflowStart(TestModels.ADAPTER_ID, "instance-9", TestModels.VERSION_TAG, true));

    final var found = bridge
        .workflowsOfAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4719");
    final var prefill = bridge.prefilledWorkflowDetails(found.getFirst()).orElseThrow();

    // this bridge holds no client of the engine at all, so a report is all a change can become
    assertEquals("instance-9", found.getFirst().workflowId());
    assertEquals("4719", prefill.businessId());
    assertEquals(TestModels.VERSION_TAG, prefill.bpmnProcessVersion());

  }

}
