package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.wiring.PeaTaskMeta;

/**
 * What a delivered user task turns into, and what it does not turn into.
 * <p>
 * The integration tests of this repository run the same way through a booted application, driven
 * by the adapter's own delivery. What they cannot provoke is the other half: a task of a process
 * this application never deployed, a delivery which names no workflow aggregate or no BPMN
 * process at all, or the end of a task this node never saw. Those are the cases where reporting
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
      final PeaDeployedProcessesRegistry deployedProcesses) {

    return new PeaCockpitObserver(
        deployedProcesses, TestModels.claimingTheRide(), deliveredUserTasks, (
            adapterId,
            workflowModuleId,
            bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, () -> publisher, TestModels.noCallers());

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
    assertNull(
        reported.processVersion(),
        "this engine wrote no version tag, and there is nothing else this BPMS could report as the version of the process");
    assertEquals(List.of(UserTaskEventKind.CREATED), publisher.userTaskKinds());
    assertEquals(List.of("task-1#create"), publisher.userTaskEventIds());
    assertTrue(
        publisher.transactions().stream().allMatch(EventTransaction.NEW::equals),
        "the engine delivers on a thread of its own, so every entry gets a transaction of its own");

    final var details = deliveredUserTasks.of("task-1").orElseThrow().details();
    assertEquals(TestModels.PROCESS_NAME, details.bpmnProcessName());
    assertEquals(TestModels.USER_TASK_NAME, details.bpmnTaskName());
    assertEquals("james", details.assignee());
    assertEquals(List.of("drivers", "dispatch"), details.candidateGroups());
    assertEquals("2026-09-09T12:00Z", String.valueOf(details.dueDate()));
    assertEquals(TestModels.DEPLOYMENT_KEY, details.bpmnProcessVersion());
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
  @DisplayName("The version tag of a delivery is the version the task and its business case are reported with")
  public void theVersionTagOfADeliveryIsReported() {

    observer
        .userTaskDelivered(
            delivery(
                "task-1", TaskInformation.CREATE, Map
                    .of(PeaTaskMeta.PROCESS_VERSION_TAG, TestModels.VERSION_TAG)));

    assertEquals(
        TestModels.VERSION_TAG,
        publisher.userTasks().getFirst().processVersion(),
        "it is what picks the details provider, so it travels with the task");
    assertEquals(
        TestModels.VERSION_TAG,
        publisher.workflows().getFirst().processVersion(),
        "the business case appears with its first task and takes the version of that task");
    assertEquals(
        TestModels.VERSION_TAG,
        deliveredUserTasks.of("task-1").orElseThrow().details().bpmnProcessVersion(),
        "and it is what a person sees next to the case, instead of the deployment key");

  }

  @Test
  @DisplayName("A delivery without a version tag reports no version, and the deployment key stays what a person reads")
  public void aDeliveryWithoutAVersionTagReportsNoVersion() {

    observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of()));

    assertNull(publisher.userTasks().getFirst().processVersion());
    assertNull(publisher.workflows().getFirst().processVersion());
    assertEquals(
        TestModels.DEPLOYMENT_KEY,
        deliveredUserTasks.of("task-1").orElseThrow().details().bpmnProcessVersion(),
        "the deployment key tells two releases of an application apart, which is worth showing and worthless to pick a method by");

  }

  /**
   * A repeated delivery is what the cockpit shows as a change of a task, and it is asserted here
   * rather than through a booted application: the in-memory engine the adapter ships delivers a
   * task without a reason, so it cannot say that a task was assigned or updated. Prompt 230 WP2 carries that change to the mock; until then the
   * end-to-end tests assert that a repeated delivery reaches the cockpit at all.
   */
  @Test
  @DisplayName("A task is created when the engine says, or else when this node first saw it delivered")
  public void aTaskKeepsTheTimeItWasCreated() {

    observer
        .userTaskDelivered(
            delivery("task-1", TaskInformation.CREATE, Map.of("creationDate", "2026-09-09T12:00:00Z")));
    assertEquals(
        "2026-09-09T12:00Z", String.valueOf(deliveredUserTasks.of("task-1").orElseThrow().details().createdAt()));

    final var beforeTheDelivery = OffsetDateTime.now();
    observer.userTaskDelivered(delivery("task-2", TaskInformation.CREATE, Map.of()));
    final var firstSeen = deliveredUserTasks.of("task-2").orElseThrow().details().createdAt();
    assertFalse(firstSeen.isBefore(beforeTheDelivery), "the task was created before it was delivered");

    observer
        .userTaskDelivered(
            delivery("task-2", TaskInformation.ASSIGN, Map.of(PeaTaskMeta.ASSIGNEE, "james")));
    assertEquals(
        firstSeen,
        deliveredUserTasks.of("task-2").orElseThrow().details().createdAt(),
        "a later delivery of the same task moved the time it was created");

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

  /**
   * The module's file carries a second process. It was deployed with the file and is recorded
   * like the first one, which is why the record of the deployment cannot tell the two apart.
   *
   * @param bpmnProcessId The id of the second process
   * @return What the adapter recorded while it deployed both
   */
  private static PeaDeployedProcessesRegistry deployedWithASecondProcess(
      final String bpmnProcessId) {

    final var deployedProcesses = TestModels.deployed();
    deployedProcesses
        .forAdapter(TestModels.ADAPTER_ID)
        .record(
            TestModels.MODULE_ID, new PeaBpmnModel(
                "a-ride.bpmn", TestModels.BPMN.getBytes(StandardCharsets.UTF_8), bpmnProcessId, "A second process", List
                    .of(), List.of(BpmnTaskSpec.userTask("review", TestModels.USER_TASK_FORM, "Review the ride"))),
            TestModels.DEPLOYMENT_KEY);
    return deployedProcesses;

  }

  private static PeaUserTaskObservation aDeliveryOf(
      final String bpmnProcessId) {

    return new PeaUserTaskObservation(
        TestModels.ADAPTER_ID, TestModels.MODULE_ID, bpmnProcessId, TestModels.USER_TASK_FORM, "4711", new TaskInformation(
            "task-1", Map.of(PeaTaskMeta.BPMN_TASK_ID, "review")), Map.of());

  }

  @Test
  @DisplayName("A task of a process deployed with the module but claimed by no workflow service is passed over")
  public void aTaskOfAProcessNobodyClaimsIsPassedOver() {

    final var observerOfTwoProcesses = new PeaCockpitObserver(
        deployedWithASecondProcess("AReview"), TestModels.claimingTheRide(), deliveredUserTasks, (
            adapterId,
            workflowModuleId,
            bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, () -> publisher, TestModels.noCallers());

    observerOfTwoProcesses.userTaskDelivered(aDeliveryOf("AReview"));

    assertTrue(
        publisher.userTasks().isEmpty(),
        "the process came with the application's file, but no @WorkflowService claims it");
    assertTrue(publisher.workflows().isEmpty());
    assertTrue(deliveredUserTasks.of("task-1").isEmpty(), "nothing is remembered for a later read");

  }

  @Test
  @DisplayName("A task of a process a workflow service claims as a secondary process is reported")
  public void aTaskOfASecondaryProcessIsReported() {

    final var observerOfTwoProcesses = new PeaCockpitObserver(
        deployedWithASecondProcess("ARideCheck"), TestModels.claiming(TestModels.BPMN_PROCESS_ID,
            "ARideCheck"), deliveredUserTasks, (
                adapterId,
                workflowModuleId,
                bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, () -> publisher, TestModels.noCallers());

    observerOfTwoProcesses.userTaskDelivered(aDeliveryOf("ARideCheck"));

    assertEquals(1, publisher.userTasks().size());
    assertEquals("ARideCheck", publisher.userTasks().getFirst().bpmnProcessId());
    assertEquals("review", publisher.userTasks().getFirst().bpmnTaskId());
    assertEquals(1, publisher.workflows().size());

  }

  private static PeaUserTaskObservation aDeliveryOf(
      final String bpmnProcessId,
      final String processInstanceId) {

    return new PeaUserTaskObservation(
        TestModels.ADAPTER_ID, TestModels.MODULE_ID, bpmnProcessId, TestModels.USER_TASK_FORM, "4711", new TaskInformation(
            "task-1", Map.of(PeaTaskMeta.BPMN_TASK_ID, "review", CommonRestrictions.PROCESS_INSTANCE_ID,
                processInstanceId)), Map
                    .of());

  }

  /**
   * An observer of the ride and the check of the car the ride calls. VanillaBP wrote down the
   * instance it started for the ride.
   */
  private PeaCockpitObserver anObserverOfARideCallingACheck(
      final boolean sharingTheAggregate) {

    final var election = new TestElection();
    election.started("4711", "instance-of-the-ride");
    return new PeaCockpitObserver(
        deployedWithASecondProcess("ARideCheck"), TestModels.claiming(TestModels.BPMN_PROCESS_ID,
            "ARideCheck"), deliveredUserTasks, (
                adapterId,
                workflowModuleId,
                bpmnProcessId) -> TestModels.DEPLOYMENT_KEY, () -> publisher, TestModels
                    .theRideCalls("ARideCheck", sharingTheAggregate, election));

  }

  @Test
  @DisplayName("A task of a called process which shares the aggregate is reported under the caller's case")
  public void aTaskOfACalledStepBelongsToTheCallersCase() {

    anObserverOfARideCallingACheck(true).userTaskDelivered(aDeliveryOf("ARideCheck", "instance-of-the-check"));

    assertEquals(1, publisher.userTasks().size());
    assertEquals("ARideCheck", publisher.userTasks().getFirst().bpmnProcessId());
    assertEquals("instance-of-the-ride", publisher.userTasks().getFirst().workflowId());
    // the case appears with this task, and it is the ride's case under the ride's process
    assertEquals(1, publisher.workflows().size());
    assertEquals(TestModels.BPMN_PROCESS_ID, publisher.workflows().getFirst().bpmnProcessId());
    assertEquals("instance-of-the-ride", publisher.workflows().getFirst().workflowId());

  }

  @Test
  @DisplayName("A task of a called process with an aggregate of its own is reported under its own case")
  public void aTaskOfACalledProcessWithItsOwnAggregateIsACase() {

    anObserverOfARideCallingACheck(false).userTaskDelivered(aDeliveryOf("ARideCheck", "instance-of-the-check"));

    assertEquals("instance-of-the-check", publisher.userTasks().getFirst().workflowId());
    assertEquals("ARideCheck", publisher.workflows().getFirst().bpmnProcessId());
    assertEquals("instance-of-the-check", publisher.workflows().getFirst().workflowId());

  }

  @Test
  @DisplayName("A task of the instance VanillaBP started is a task of that case")
  public void aTaskOfTheStartedInstanceBelongsToIt() {

    anObserverOfARideCallingACheck(true)
        .userTaskDelivered(aDeliveryOf(TestModels.BPMN_PROCESS_ID, "instance-of-the-ride"));

    assertEquals("instance-of-the-ride", publisher.userTasks().getFirst().workflowId());
    assertEquals(TestModels.BPMN_PROCESS_ID, publisher.workflows().getFirst().bpmnProcessId());
    assertEquals("instance-of-the-ride", publisher.workflows().getFirst().workflowId());

  }

  /** How the warning about two different ids names the instance of the task. */
  private static final String TWO_IDS = "but it delivers a user task of that workflow as one of process instance '%s'";

  private static PeaUserTaskObservation aDeliveryOf(
      final String taskId,
      final String bpmnProcessId,
      final String processInstanceId) {

    return new PeaUserTaskObservation(
        TestModels.ADAPTER_ID, TestModels.MODULE_ID, bpmnProcessId, TestModels.USER_TASK_FORM, "4711", new TaskInformation(
            taskId, Map.of(PeaTaskMeta.BPMN_TASK_ID, "review", CommonRestrictions.PROCESS_INSTANCE_ID,
                processInstanceId)), Map
                    .of());

  }

  private static long linesAbout(
      final CapturedOutput output,
      final String processInstanceId) {

    return output
        .getAll()
        .lines()
        .filter(line -> line.contains(TWO_IDS.formatted(processInstanceId)))
        .count();

  }

  @Test
  @DisplayName("A task in another instance than the one the start named is said out loud, once per instance")
  public void aTaskInAnotherInstanceThanTheStartIsSaidOnce(
      final CapturedOutput output) {

    // the ride calls nothing which shares its aggregate, so this task is no step. Its engine
    // names another instance than the one it answered the start with
    final var observer = anObserverOfARideCallingACheck(false);
    observer.userTaskDelivered(aDeliveryOf("task-1", TestModels.BPMN_PROCESS_ID, "instance-of-a-broken-engine"));
    observer.userTaskDelivered(aDeliveryOf("task-2", TestModels.BPMN_PROCESS_ID, "instance-of-a-broken-engine"));

    assertEquals(1, linesAbout(output, "instance-of-a-broken-engine"), output.getAll());
    // the task is still reported, under the id it names
    assertEquals("instance-of-a-broken-engine", publisher.userTasks().getFirst().workflowId());
    assertEquals(2, publisher.userTasks().size());

  }

  @Test
  @DisplayName("A task of a called step names another instance than the start, and that is no reason to warn")
  public void aTaskOfACalledStepIsNoReasonToWarn(
      final CapturedOutput output) {

    anObserverOfARideCallingACheck(true)
        .userTaskDelivered(aDeliveryOf("task-1", "ARideCheck", "instance-of-a-called-check"));
    // the same for a called process with an aggregate of its own: VanillaBP never started it
    anObserverOfARideCallingACheck(false)
        .userTaskDelivered(aDeliveryOf("task-2", "ARideCheck", "instance-of-a-check-of-its-own"));

    assertEquals(0, linesAbout(output, "instance-of-a-called-check"), output.getAll());
    assertEquals(0, linesAbout(output, "instance-of-a-check-of-its-own"), output.getAll());

  }

  @Test
  @DisplayName("A task in the instance the start named is no reason to warn")
  public void aTaskInTheStartedInstanceIsNoReasonToWarn(
      final CapturedOutput output) {

    anObserverOfARideCallingACheck(false)
        .userTaskDelivered(aDeliveryOf("task-1", TestModels.BPMN_PROCESS_ID, "instance-of-the-ride"));

    assertEquals(0, linesAbout(output, "instance-of-the-ride"), output.getAll());

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
  @DisplayName("The name of a user task is the one the adapter read off the model it deployed")
  public void theTaskNameComesFromTheDeployedModel() {

    observer = observerOf(TestModels.deployed(TestModels.model("Approve it, please")));

    observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of()));

    assertEquals(
        "Approve it, please",
        deliveredUserTasks.of("task-1").orElseThrow().details().bpmnTaskName());

  }

  @Test
  @DisplayName("A user task the engine names itself is shown under the engine's name")
  public void theEngineOutranksTheDeployedModel() {

    observer
        .userTaskDelivered(
            delivery(
                "task-1", TaskInformation.CREATE, Map
                    .of(PeaTaskMeta.TASK_NAME, "Approve the ride, renamed")));

    assertEquals(
        "Approve the ride, renamed",
        deliveredUserTasks.of("task-1").orElseThrow().details().bpmnTaskName(),
        "what the engine says a task is called is newer than what was deployed");

  }

  @Test
  @DisplayName("A user task nobody named is reported without a name rather than without a task")
  public void anUnnamedUserTaskIsStillReported() {

    observer = observerOf(TestModels.deployed(TestModels.model(null)));

    observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of()));

    assertEquals(1, publisher.userTasks().size());
    assertNull(deliveredUserTasks.of("task-1").orElseThrow().details().bpmnTaskName());

  }

  @Test
  @DisplayName("A task of an engine which deployed nothing is passed over")
  public void aTaskOfAnotherEngineIsPassedOver() {

    observer
        .userTaskDelivered(
            new PeaUserTaskObservation(
                "another-pea", TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, TestModels.USER_TASK_FORM, "4711", new TaskInformation(
                    "task-1", Map.of()), Map.of()));

    assertTrue(
        publisher.userTasks().isEmpty(),
        "what one engine deployed says nothing about what another one runs");
    assertTrue(publisher.workflows().isEmpty());

  }

  @Test
  @DisplayName("A workflow the engine does not identify is reported under the aggregate it belongs to")
  public void aWorkflowWithoutAnIdentityIsReportedUnderItsAggregate() {

    observer
        .userTaskDelivered(
            new PeaUserTaskObservation(
                TestModels.ADAPTER_ID, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, TestModels.USER_TASK_FORM, "4711", new TaskInformation(
                    "task-1", Map.of()), Map.of()));

    assertEquals("4711", publisher.userTasks().getFirst().workflowId());
    assertEquals("4711", publisher.workflows().getFirst().workflowId());

  }

  @Test
  @DisplayName("A business case whose report was refused is reported again with the next task of it")
  public void aWorkflowIsReportedAgainWhenItsReportWasRefused() {

    publisher.refuseWorkflowEvents();
    assertThrows(
        IllegalStateException.class,
        () -> observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of())));
    publisher.takeEventsAgain();

    observer.userTaskDelivered(delivery("task-2", TaskInformation.CREATE, Map.of()));

    assertEquals(
        List.of(WorkflowEventKind.CREATED),
        publisher.workflowKinds(),
        "the case was noted as reported only after the report was written, so the next task of it reported it");

  }

  @Test
  @DisplayName("A task whose end could not be reported is still open, so a second end reports it")
  public void aTerminationIsReportedAgainWhenItsReportWasRefused() {

    observer.userTaskDelivered(delivery("task-1", TaskInformation.CREATE, Map.of()));
    publisher.userTaskKinds().clear();
    publisher.refuseUserTaskEvents();

    assertThrows(
        IllegalStateException.class, () -> aTerminatedUserTask("task-1", TaskInformation.COMPLETE));

    assertEquals(
        List.of("task-1"),
        deliveredUserTasks
            .ofAggregate(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, "4711")
            .stream()
            .map(task -> task.reference().userTaskId())
            .toList(),
        "a report which was never written leaves the task as it was, one of its case's open tasks");

    publisher.takeEventsAgain();
    aTerminatedUserTask("task-1", TaskInformation.COMPLETE);

    assertEquals(List.of(UserTaskEventKind.COMPLETED), publisher.userTaskKinds());

  }

  @Test
  @DisplayName("A due date which is not a timestamp costs the date and not the task")
  public void anUnreadableDateIsDropped() {

    observer
        .userTaskDelivered(
            delivery("task-1", TaskInformation.CREATE, Map.of(PeaTaskMeta.DUE_DATE, "tomorrow")));

    assertEquals(1, publisher.userTasks().size());
    assertNull(deliveredUserTasks.of("task-1").orElseThrow().details().dueDate());

  }

}
