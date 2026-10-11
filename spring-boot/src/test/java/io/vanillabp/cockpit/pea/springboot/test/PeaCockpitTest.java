package io.vanillabp.cockpit.pea.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.wiring.PeaTaskMeta;

/**
 * Tests the Business Cockpit on the Process-Engine-API, from the delivery of a user task to the
 * request the cockpit server receives.
 * <p>
 * Only the cockpit server and the engine are fakes on that path. The workflow is started through
 * VanillaBP. The engine delivers a user task to the ADAPTER's own subscription, and the adapter
 * hands the delivery to the observer bean this extension contributes. The application's details
 * provider runs right there. The finished report is written into the application's outbox, and
 * the test checks what arrives at the server.
 * <p>
 * The engine says little about a task, as an in-memory engine does: the process instance it
 * answered the start with, and no assignee and no reason. That is on purpose. The unit tests of
 * the neutral module check what the cockpit makes of a full meta map. Only a booted application
 * shows that a delivery reaches this extension at all.
 * <p>
 * The engine finds the instance of a task by the variables the workflow was started with, so a
 * task carries the aggregate's id with its real type. A test which passes the id as text plays an
 * engine which names no instance at all.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class PeaCockpitTest {

  private static final String ADAPTER_ID = "pea";

  /**
   * How often the application tries a transaction which ran into a conflict. Two tries would be
   * enough for the one other writer. The third one is there so that a repetition which runs into
   * the next report does not end the test.
   */
  private static final int ATTEMPTS_OF_THE_APPLICATION = 3;

  @DynamicPropertySource
  static void cockpitServer(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.cockpit.rest.base-url", CockpitServer::baseUrl);

  }

  @Autowired
  private TestWorkflowService workflowService;

  @Autowired
  private TestAggregateRepository aggregates;

  @Autowired
  private TransactionTemplate transactions;

  @Autowired
  private InMemoryProcessEngine engine;

  @Autowired
  private ObjectProvider<BusinessCockpitBpmsBridge> bridges;

  /** VanillaBP's election, which says what id it wrote down when it started a workflow. */
  @Autowired
  private WorkflowElection election;

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();
    engine.clearTaskRecordings();

  }

  /**
   * Changes one case and reports the change. An application whose workflow aggregate has a
   * version attribute has to do it this way: in a transaction which is repeated if somebody else
   * wrote the same case in between.
   * <p>
   * That somebody is the Business Cockpit itself. Its details provider reads the case while a
   * report is built and writes it back when that transaction commits. So the two transactions
   * overlap whenever an application changes a case while a delivery of it is being reported.
   * Without the version attribute, the later of the two writers wins without a word. With it, one
   * of them runs into a conflict and repeats.
   *
   * @param aggregateId The case to change
   * @param changeAndReport Changes the attached case and reports it to the cockpit
   */
  private void changeTheCase(
      final Long aggregateId,
      final Consumer<TestAggregate> changeAndReport) {

    for (var attempt = 1;; attempt++) {
      try {
        transactions
            .executeWithoutResult(status -> {
              final var loaded = aggregates.findById(aggregateId).orElseThrow();
              changeAndReport.accept(loaded);
              aggregates.save(loaded);
            });
        return;
      } catch (final OptimisticLockingFailureException e) {
        if (attempt >= ATTEMPTS_OF_THE_APPLICATION) {
          throw new AssertionError(
              "The application gave up after %d attempts at changing case %s"
                  .formatted(attempt, aggregateId), e);
        }
      }
    }

  }

  /**
   * Waits for a report of one kind which carries the value the application wrote.
   * <p>
   * A report with the older value has two possible causes, and each needs a different fix: the
   * report read the case too early, or the case itself lost the change. So a failure also names
   * what the case holds now. See the version attribute of {@link TestAggregate}.
   *
   * @param pathSuffix What the report's path has to end with
   * @param expected What its body has to carry
   * @param aggregate The case the report is about
   * @return The report
   */
  private CockpitServer.Request awaitReportCarrying(
      final String pathSuffix,
      final String expected,
      final TestAggregate aggregate) {

    try {
      return CockpitServer.awaitRequest(pathSuffix, expected);
    } catch (final AssertionError e) {
      throw new AssertionError(
          "%s And the stored case now carries the customer '%s'."
              .formatted(
                  e.getMessage(),
                  transactions
                      .execute(
                          status -> aggregates
                              .findById(aggregate.getId())
                              .orElseThrow()
                              .getCustomer())), e);
    }

  }

  /**
   * Starts a workflow and waits until the start reached the engine. Only then can the engine tell
   * which instance a task belongs to.
   */
  private TestAggregate aStartedWorkflow(
      final String customer) {

    final var started = transactions
        .execute(status -> {
          final var aggregate = new TestAggregate();
          aggregate.setCustomer(customer);
          return workflowService.processes().startWorkflow(aggregate);
        });
    awaitTheStartedWorkflowOf(started);
    return started;

  }

  /**
   * The id the cockpit shows a business case under. In every task of an instance, this engine
   * names the id it answered the start with. So it is the id VanillaBP wrote down at the start.
   */
  private String workflowIdOf(
      final TestAggregate aggregate) {

    return awaitTheStartedWorkflowOf(aggregate);

  }

  /**
   * Makes the engine deliver a user task to the adapter's own subscription, as a
   * Process-Engine-API engine does when a workflow reaches one.
   */
  private void aDeliveredUserTask(
      final TestAggregate aggregate,
      final String taskId) {

    engine
        .deliverTask(
            taskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", aggregate.getId()));

  }

  /**
   * Like {@link #aDeliveredUserTask}, but as an engine which names no process instance delivers
   * it. The Process-Engine-API promises the same id at the start and in a task, but it does not
   * force an engine to name one in a task at all. The in-memory engine names the instance whose
   * start variables have the same value and type as the variables of the task. The aggregate's id
   * as text matches no start, so the task names no instance.
   */
  private void aDeliveredUserTaskWhichNamesNoInstance(
      final TestAggregate aggregate,
      final String taskId) {

    engine
        .deliverTask(
            taskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())));

  }

  /**
   * The time at which, according to the report of an end, its user task was created. Only the
   * report of an end carries it.
   *
   * @param report The report as the cockpit server received it
   * @return The time the report names, as an instant
   */
  private static Instant startNamedBy(
      final CockpitServer.Request report) {

    final var createdAt = Pattern.compile("\"createdAt\":\"([^\"]+)\"").matcher(report.body());
    assertTrue(createdAt.find(), "the report of an end did not say when the task was created: "
        + report.body());
    return OffsetDateTime.parse(createdAt.group(1)).toInstant();

  }

  @Test
  @DisplayName("A delivered user task and its business case reach the cockpit, enriched by the application")
  public void aDeliveredUserTaskReachesTheCockpit() {

    final var aggregate = aStartedWorkflow("Anna");

    aDeliveredUserTask(aggregate, "task-1");

    final var workflow = CockpitServer
        .awaitRequest(
            "/workflow/created", "\"workflowId\":\"%s\"".formatted(workflowIdOf(aggregate)));
    assertTrue(workflow.body().contains("\"customer\":\"Anna\""), workflow.body());
    assertTrue(workflow.body().contains(TestWorkflowService.BPMN_PROCESS_ID), workflow.body());

    final var userTask = CockpitServer
        .awaitRequest("/usertask/created", "\"userTaskId\":\"task-1\"");
    assertTrue(userTask.body().contains("\"customer\":\"Anna\""), userTask.body());
    assertTrue(userTask.body().contains("\"event\":\"CREATED\""), userTask.body());
    assertTrue(
        userTask
            .body()
            .contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("\"candidateGroups\":[\"drivers\"]"), userTask.body());
    // the BPMN name is what the cockpit falls back to when nothing else produced a title
    assertTrue(userTask.body().contains("Approve the ride"), userTask.body());

    // The details provider read the customer in that body from the case, so the report shows
    // that the provider ran on the real aggregate. Whether the case KEEPS what that provider
    // wrote into it is not asserted, on purpose. The write is part of the transaction which builds
    // the report and writes the entry. Another writer of the same case can make that commit fail
    // through the version attribute. Then the entry and the write are both gone, so a test which
    // waits for that write waits for something it does not control.

  }

  @Test
  @DisplayName("A details provider gets a variable no workflow task of its module reads")
  public void aDetailsProviderGetsAVariableNoWorkflowTaskReads() {

    final var aggregate = aStartedWorkflow("Paula");

    // the subscription also asks the engine for what the cockpit reads. Nothing else would: the
    // only workflow task of this user task reads no variable at all
    final var askedFor = engine
        .getSubscriptions()
        .stream()
        .filter(subscription -> subscription
            .taskDescriptionKey()
            .equals(TestWorkflowService.TASK_DEFINITION))
        .map(InMemoryProcessEngine.ActiveSubscription::payloadDescription)
        .toList();
    assertFalse(askedFor.isEmpty(), "nothing subscribed to the user task");
    assertTrue(
        askedFor.stream().allMatch(names -> names.contains(TestWorkflowService.PASSENGER_VARIABLE)),
        "the subscriptions ask for "
            + askedFor);

    // the engine narrows the payload to what was asked for, as a real engine does
    engine
        .deliverTask(
            "task-passenger", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", aggregate.getId(), TestWorkflowService.PASSENGER_VARIABLE, "Paul"));

    final var userTask = CockpitServer
        .awaitRequest("/usertask/created", "\"userTaskId\":\"task-passenger\"");
    assertTrue(userTask.body().contains("\"passenger\":\"Paul\""), userTask.body());

  }

  @Test
  @DisplayName("A task the engine delivers again reaches the cockpit again")
  public void aRepeatedDeliveryReachesTheCockpitAgain() {

    final var aggregate = aStartedWorkflow("Rita");
    aDeliveredUserTask(aggregate, "task-6");
    CockpitServer.awaitRequestOf("/usertask/created", "\"userTaskId\":\"task-6\"");
    CockpitServer.forgetRequests();

    // the engine repeats a delivery whenever something about the task changed: its assignee, its
    // candidates or its data. The cockpit is told again. The reason the engine names decides
    // whether the cockpit hears "created" or "updated". This test delivers with no reason, so a
    // repeated delivery arrives here as a second creation. The unit tests of the neutral module
    // show that the kind follows the reason.
    aDeliveredUserTask(aggregate, "task-6");

    final var again = CockpitServer
        .awaitRequest("/usertask/created", "\"userTaskId\":\"task-6\"");
    assertTrue(again.body().contains("\"customer\":\"Rita\""), again.body());

  }

  @Test
  @DisplayName("A task which is gone is reported as completed")
  public void aTerminatedUserTaskIsReportedAsCompleted() {

    final var aggregate = aStartedWorkflow("Cleo");
    // a report may carry milliseconds only
    final var beforeTheDelivery = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    aDeliveredUserTask(aggregate, "task-3");
    CockpitServer.awaitRequestOf("/usertask/created", "\"userTaskId\":\"task-3\"");
    final var afterTheDelivery = Instant.now();

    engine
        .terminateTask(
            "task-3", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID,
            TaskInformation.COMPLETE);

    // the report of the end says when the task was created, for a cockpit which gets the end
    // before the creation. This engine names no creation time, so it is the time the node first
    // saw the task delivered
    final var completed = CockpitServer.awaitAnyRequest("/usertask/task-3/completed");
    assertNotNull(completed);
    final var createdAt = startNamedBy(completed);
    assertTrue(
        !createdAt.isBefore(beforeTheDelivery) && !createdAt.isAfter(afterTheDelivery),
        "the task was created at %s, outside of its delivery".formatted(createdAt));

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates the business case it knows")
  public void aggregateChangedUpdatesTheWorkflow() {

    final var aggregate = aStartedWorkflow("Dora");
    aDeliveredUserTask(aggregate, "task-4");
    CockpitServer
        .awaitRequestOf(
            "/workflow/created", "\"workflowId\":\"%s\"".formatted(workflowIdOf(aggregate)));
    CockpitServer.forgetRequests();

    changeTheCase(
        aggregate.getId(),
        loaded -> {
          loaded.setCustomer("Dora the second");
          workflowService.businessCockpit().aggregateChanged(loaded);
        });

    // the report carries what the application wrote, not what the case held before. The report
    // of the user task may still be built while this runs, and its details provider holds the
    // case during this transaction. The version attribute of TestAggregate stops that provider
    // from writing the older state back
    awaitReportCarrying(
        "/workflow/%s/updated".formatted(workflowIdOf(aggregate)),
        "\"customer\":\"Dora the second\"",
        aggregate);

  }

  @Test
  @DisplayName("On an engine which names no instance, a changed aggregate updates the case under the aggregate's id")
  public void aggregateChangedUpdatesACaseOfAnEngineWhichNamesNoInstance() {

    final var aggregate = aStartedWorkflow("Hanna");
    final var aggregateId = String.valueOf(aggregate.getId());
    aDeliveredUserTaskWhichNamesNoInstance(aggregate, "task-8");
    // the case appears under the aggregate's id (decision 6), not under the id of the start
    CockpitServer.awaitRequestOf("/workflow/created", "\"workflowId\":\"%s\"".formatted(aggregateId));
    CockpitServer.forgetRequests();

    changeTheCase(
        aggregate.getId(),
        loaded -> {
          loaded.setCustomer("Hanna the second");
          workflowService.businessCockpit().aggregateChanged(loaded);
        });

    // the open task wins over the id of the start, so the update goes to the case the cockpit
    // knows. See decision 22 in the repository's DECISIONS.md
    awaitReportCarrying(
        "/workflow/%s/updated".formatted(aggregateId), "\"customer\":\"Hanna the second\"", aggregate);
    CockpitServer.awaitQuiet();
    assertTrue(
        CockpitServer.matching("/workflow/%s/updated".formatted(workflowIdOf(aggregate))).isEmpty(),
        "the change went out under the id of the start as well, which the cockpit does not know");

  }

  /**
   * Waits for the id VanillaBP writes down once phase two of the start reached the engine.
   *
   * @param aggregate The case whose workflow was started
   * @return The engine's id of the workflow
   */
  private String awaitTheStartedWorkflowOf(
      final TestAggregate aggregate) {

    final var giveUpAt = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < giveUpAt) {
      final var workflowId = election
          .workflowIdOf(
              TestApplication.MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregate.getId());
      if (workflowId.isPresent()) {
        return workflowId.get();
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for the start of aggregate "
            + aggregate.getId(), e);
      }
    }
    throw new AssertionError(
        "VanillaBP wrote down no start of the workflow of aggregate %s within 30 seconds"
            .formatted(aggregate.getId()));

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates a business case which has no open user task")
  public void aggregateChangedUpdatesAWorkflowWithoutAnOpenTask() {

    final var aggregate = aStartedWorkflow("Fritz");
    final var workflowId = workflowIdOf(aggregate);
    // the engine names the instance it answered the start with, as every task of it does. It
    // also names a version tag: without one the case has no version, and then decision 16 in the
    // repository's DECISIONS.md reports no change
    engine
        .deliverTask(
            "task-7", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", aggregate.getId()),
            Map.of(PeaTaskMeta.PROCESS_VERSION_TAG, "1.0.0"));
    CockpitServer.awaitRequestOf("/workflow/created", "\"workflowId\":\"%s\"".formatted(workflowId));
    engine
        .terminateTask(
            "task-7", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID,
            TaskInformation.COMPLETE);
    CockpitServer.awaitAnyRequest("/usertask/task-7/completed");
    CockpitServer.forgetRequests();

    // the workflow has moved on and no user task of it is open now
    changeTheCase(
        aggregate.getId(),
        loaded -> {
          loaded.setCustomer("Fritz the second");
          workflowService.businessCockpit().aggregateChanged(loaded);
        });

    awaitReportCarrying(
        "/workflow/%s/updated".formatted(workflowId), "\"customer\":\"Fritz the second\"", aggregate);

  }

  @Test
  @DisplayName("A task of a called process which shares the aggregate is filed under the caller's case")
  public void aTaskOfACalledStepIsFiledUnderTheCallersCase() {

    final var aggregate = aStartedWorkflow("Greta");
    final var workflowId = workflowIdOf(aggregate);

    // the first task of this case is in the called process. The engine names the instance of the
    // called process, which is not the one VanillaBP started. See decision 21 in the
    // repository's DECISIONS.md
    engine
        .deliverTask(
            "task-check", TestWorkflowService.CALLED_TASK_DEFINITION, TestWorkflowService.CALLED_BPMN_PROCESS_ID, Map
                .of("id", aggregate.getId()),
            Map.of(CommonRestrictions.PROCESS_INSTANCE_ID, "the-called-instance"));

    final var userTask = CockpitServer.awaitRequest("/usertask/created", "\"userTaskId\":\"task-check\"");
    assertTrue(userTask.body().contains("\"workflowId\":\"%s\"".formatted(workflowId)), userTask.body());
    assertTrue(
        userTask.body().contains("\"bpmnProcessId\":\"%s\"".formatted(TestWorkflowService.CALLED_BPMN_PROCESS_ID)),
        userTask.body());
    // the case appears with that task, as the caller's case and under the caller's process
    final var workflow = CockpitServer
        .awaitRequest("/workflow/created", "\"workflowId\":\"%s\"".formatted(workflowId));
    assertTrue(
        workflow.body().contains("\"bpmnProcessId\":\"%s\"".formatted(TestWorkflowService.BPMN_PROCESS_ID)),
        workflow.body());
    CockpitServer.awaitQuiet();
    assertTrue(
        CockpitServer
            .matching("/workflow/created")
            .stream()
            .noneMatch(request -> request.body().contains("the-called-instance")),
        "the called process became a case of its own");

  }

  @Test
  @DisplayName("A user task is readable while this node holds it and gone afterwards")
  public void aUserTaskIsReadableWhileItIsHeld() {

    final var aggregate = aStartedWorkflow("Emil");
    aDeliveredUserTask(aggregate, "task-5");

    final var userTask = transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(aggregates.findById(aggregate.getId()).orElseThrow(), "task-5"));
    assertTrue(userTask.isPresent());
    assertEquals("task-5", userTask.get().getId());
    assertEquals(TestWorkflowService.TASK_DEFINITION, userTask.get().getTaskDefinition());

    engine
        .terminateTask(
            "task-5", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, TaskInformation.DELETE);

    assertTrue(
        transactions
            .execute(
                status -> workflowService
                    .businessCockpit()
                    .getUserTask(
                        aggregates.findById(aggregate.getId()).orElseThrow(), "task-5"))
            .isEmpty(),
        "what the engine took away is gone, and this BPMS cannot be asked what it was");

  }

  @Test
  @DisplayName("The workflow module registers itself at the cockpit server")
  public void theWorkflowModuleIsRegistered() {

    final var registration = CockpitServer.awaitRegistration();

    assertTrue(
        registration.path().contains(TestApplication.MODULE_ID), registration.path());
    assertTrue(registration.body().contains("drivers"), registration.body());

  }

  @Test
  @DisplayName("One bridge per configured Process-Engine-API adapter id serves the cockpit")
  public void oneBridgePerConfiguredAdapterId() {

    final var registered = bridges.stream().toList();

    assertEquals(1, registered.size());
    assertEquals(ADAPTER_ID, registered.getFirst().adapterId());
    assertEquals(PeaAdapter.ADAPTER_TYPE, registered.getFirst().adapterType());

  }

}
