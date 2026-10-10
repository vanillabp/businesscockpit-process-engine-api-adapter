package io.vanillabp.cockpit.pea.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.wiring.PeaTaskMeta;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The Process-Engine-API half of the Business Cockpit inside a booted Quarkus application. The
 * extension is enabled. The engine delivers a user task to the ADAPTER's own subscription, the
 * adapter hands it to the observer bean this extension contributes, and what the application
 * enriched reaches the cockpit server. The bridge then answers what the cockpit reads back.
 * <p>
 * It runs the same way through as the Spring Boot test of this repository. It exists because a
 * platform-neutral half being right says nothing about a platform's glue ever calling it.
 * <p>
 * The engine names the instance of a task by the variables the workflow was started with, so a
 * task carries the aggregate's id with its real type. A test which hands it over as text plays an
 * engine which names no instance at all.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class PeaCockpitTest {

  private static final String ADAPTER_ID = "pea";

  private static final String MODULE_ID = "pea-cockpit";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = TestApplication.forTestClass(PeaCockpitTest.class);

  @Inject
  TestWorkflowService workflowService;

  @Inject
  TestAggregatePersistence aggregates;

  @Inject
  InMemoryProcessEngine engine;

  @Inject
  List<BusinessCockpitBpmsBridge> bridges;

  @Inject
  UserTransaction transaction;

  /** VanillaBP's election, which says what id it wrote down when it started a workflow. */
  @Inject
  WorkflowElection election;

  /**
   * Starts a workflow and waits until the start reached the engine. Only then can the engine tell
   * which instance a task belongs to.
   */
  private TestAggregate aStartedWorkflow(
      final String customer) throws Exception {

    transaction.begin();
    final TestAggregate started;
    try {
      final var aggregate = new TestAggregate();
      aggregate.setCustomer(customer);
      started = workflowService.processes().startWorkflow(aggregate);
      transaction.commit();
    } catch (final RuntimeException e) {
      transaction.rollback();
      throw e;
    }
    awaitTheStartedWorkflowOf(started);
    return started;

  }

  /**
   * The identifier the cockpit shows a business case under. This engine names the instance it
   * answered the start with in every task of it, so it is the id VanillaBP wrote down at the start.
   */
  private String workflowIdOf(
      final TestAggregate aggregate) throws InterruptedException {

    return awaitTheStartedWorkflowOf(aggregate);

  }

  /**
   * Makes the engine deliver a user task to the adapter's own subscription, the way a
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
   * Like {@link #aDeliveredUserTask}, as an engine delivers it which names no process instance.
   * The Process-Engine-API promises the same id at the start and in a task, but it does not make
   * an engine name one in a task at all. The in-memory engine names the instance whose start
   * variables a task carries with the same value and type. The aggregate's id as text matches no
   * start, so the task names no instance.
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
   * Changes the customer of a case and reports the change to the cockpit.
   */
  private void changeTheCustomer(
      final TestAggregate aggregate,
      final String customer) throws Exception {

    transaction.begin();
    try {
      final var loaded = aggregates.byId(aggregate.getId());
      loaded.setCustomer(customer);
      aggregates.save(loaded);
      workflowService.businessCockpit().aggregateChanged(loaded);
      transaction.commit();
    } catch (final RuntimeException e) {
      transaction.rollback();
      throw e;
    }

  }

  /**
   * When the report of an end says its user task was created. Only the report of an end carries
   * it.
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
  public void aDeliveredUserTaskReachesTheCockpit() throws Exception {

    CockpitServer.forgetRequests();

    final var aggregate = aStartedWorkflow("Anna");
    aDeliveredUserTask(aggregate, "task-1");

    final var workflow = CockpitServer
        .awaitRequest(
            "/workflow/created", "\"workflowId\":\"%s\"".formatted(workflowIdOf(aggregate)));
    assertTrue(workflow.body().contains("\"customer\":\"Anna\""), workflow.body());

    final var userTask = CockpitServer
        .awaitRequest("/usertask/created", "\"userTaskId\":\"task-1\"");
    assertTrue(userTask.body().contains("\"customer\":\"Anna\""), userTask.body());
    assertTrue(userTask.body().contains("Approve the ride"), userTask.body());

    assertEquals(
        TestWorkflowService.APPROVE_NOTE, aggregates.byId(aggregate.getId()).getNote());

  }

  @Test
  @DisplayName("A details provider gets a variable no workflow task of its module reads")
  public void aDetailsProviderGetsAVariableNoWorkflowTaskReads() throws Exception {

    CockpitServer.forgetRequests();

    final var aggregate = aStartedWorkflow("Paula");

    // the subscription asks the engine for what the cockpit reads as well. Nothing else would:
    // the only workflow task of this user task reads no variable at all
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

    // the engine narrows the payload to what was asked for, the way a real engine does
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
  public void aRepeatedDeliveryReachesTheCockpitAgain() throws Exception {

    final var aggregate = aStartedWorkflow("Rita");
    aDeliveredUserTask(aggregate, "task-6");
    CockpitServer.awaitRequestOf("/usertask/created", "\"userTaskId\":\"task-6\"");
    CockpitServer.forgetRequests();

    // the engine repeats a delivery whenever something about the task changed - its assignee,
    // its candidates or its data - and the cockpit is told again. WHICH of the two it is told,
    // created or updated, is decided by the reason the engine names, and the in-memory engine
    // this test runs against names none: it delivers with no reason, so a repeated delivery
    // arrives here as a second creation. That the kind follows
    // the reason is asserted in the unit tests of the neutral module, and prompt 230 WP2
    // carries the change which would let this engine drive it.
    aDeliveredUserTask(aggregate, "task-6");

    final var again = CockpitServer
        .awaitRequest("/usertask/created", "\"userTaskId\":\"task-6\"");
    assertTrue(again.body().contains("\"customer\":\"Rita\""), again.body());

  }

  @Test
  @DisplayName("A task which is gone is reported as completed and is not readable any more")
  public void aTerminatedUserTaskIsReportedAsCompleted() throws Exception {

    CockpitServer.forgetRequests();

    final var aggregate = aStartedWorkflow("Bert");
    // a report may carry milliseconds only
    final var beforeTheDelivery = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    aDeliveredUserTask(aggregate, "task-2");
    CockpitServer.awaitRequestOf("/usertask/created", "\"userTaskId\":\"task-2\"");
    final var afterTheDelivery = Instant.now();

    assertTrue(
        workflowService
            .businessCockpit()
            .getUserTask(aggregates.byId(aggregate.getId()), "task-2")
            .isPresent());

    engine
        .terminateTask(
            "task-2", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID,
            TaskInformation.COMPLETE);

    // the report of the end says when the task was created, for the cockpit which gets the end
    // before the creation. This engine names no creation time, so it is when the node first saw
    // the task delivered
    final var completed = CockpitServer.awaitAnyRequest("/usertask/task-2/completed");
    assertNotNull(completed);
    final var createdAt = startNamedBy(completed);
    assertTrue(
        !createdAt.isBefore(beforeTheDelivery) && !createdAt.isAfter(afterTheDelivery),
        "the task was created at %s, outside of its delivery".formatted(createdAt));
    assertTrue(
        workflowService
            .businessCockpit()
            .getUserTask(aggregates.byId(aggregate.getId()), "task-2")
            .isEmpty(),
        "what the engine took away is gone, and this BPMS cannot be asked what it was");

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates the business case it knows")
  public void aggregateChangedUpdatesTheWorkflow() throws Exception {

    final var aggregate = aStartedWorkflow("Cleo");
    aDeliveredUserTask(aggregate, "task-3");
    CockpitServer
        .awaitRequestOf(
            "/workflow/created", "\"workflowId\":\"%s\"".formatted(workflowIdOf(aggregate)));
    CockpitServer.forgetRequests();

    transaction.begin();
    try {
      final var loaded = aggregates.byId(aggregate.getId());
      loaded.setCustomer("Cleo the second");
      aggregates.save(loaded);
      workflowService.businessCockpit().aggregateChanged(loaded);
      transaction.commit();
    } catch (final RuntimeException e) {
      transaction.rollback();
      throw e;
    }

    final var updated = CockpitServer
        .awaitAnyRequest("/workflow/%s/updated".formatted(workflowIdOf(aggregate)));
    assertTrue(updated.body().contains("\"customer\":\"Cleo the second\""), updated.body());

  }

  @Test
  @DisplayName("On an engine which names no instance, a changed aggregate updates the case under the aggregate's id")
  public void aggregateChangedUpdatesACaseOfAnEngineWhichNamesNoInstance() throws Exception {

    final var aggregate = aStartedWorkflow("Hanna");
    final var aggregateId = String.valueOf(aggregate.getId());
    aDeliveredUserTaskWhichNamesNoInstance(aggregate, "task-8");
    // the case appears under the aggregate's id (decision 6), not under the id of the start
    CockpitServer.awaitRequestOf("/workflow/created", "\"workflowId\":\"%s\"".formatted(aggregateId));
    CockpitServer.forgetRequests();

    changeTheCustomer(aggregate, "Hanna the second");

    // the open task answers before the id of the start, so the update goes to the case the
    // cockpit knows. See decision 22 in the repository's DECISIONS.md
    final var updated = CockpitServer.awaitAnyRequest("/workflow/%s/updated".formatted(aggregateId));
    assertTrue(updated.body().contains("\"customer\":\"Hanna the second\""), updated.body());
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
      final TestAggregate aggregate) throws InterruptedException {

    final var giveUpAt = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < giveUpAt) {
      final var workflowId = election
          .workflowIdOf(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregate.getId());
      if (workflowId.isPresent()) {
        return workflowId.get();
      }
      Thread.sleep(50);
    }
    throw new AssertionError(
        "VanillaBP wrote down no start of the workflow of aggregate %s within 30 seconds"
            .formatted(aggregate.getId()));

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates a business case which has no open user task")
  public void aggregateChangedUpdatesAWorkflowWithoutAnOpenTask() throws Exception {

    final var aggregate = aStartedWorkflow("Fritz");
    final var workflowId = workflowIdOf(aggregate);
    // the engine names the instance it answered the start with, as every task of it does. It
    // names a version tag as well: without one the case has no version, and decision 16 in the
    // repository's DECISIONS.md reports no change then
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
    assertEquals(
        List.of(workflowId),
        bridges
            .getFirst()
            .workflowsOfAggregate(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()))
            .stream()
            .map(workflow -> workflow.workflowId())
            .toList(),
        "no user task of the case is open, so the id VanillaBP wrote down at the start names its workflow");
    transaction.begin();
    try {
      final var loaded = aggregates.byId(aggregate.getId());
      loaded.setCustomer("Fritz the second");
      aggregates.save(loaded);
      workflowService.businessCockpit().aggregateChanged(loaded);
      transaction.commit();
    } catch (final RuntimeException e) {
      transaction.rollback();
      throw e;
    }

    final var updated = CockpitServer
        .awaitAnyRequest("/workflow/%s/updated".formatted(workflowId));
    assertTrue(updated.body().contains("\"customer\":\"Fritz the second\""), updated.body());

  }

  @Test
  @DisplayName("The workflow module registers itself at the cockpit server")
  public void theWorkflowModuleIsRegistered() {

    final var registration = CockpitServer.awaitRegistration();

    assertTrue(registration.path().contains(MODULE_ID), registration.path());
    assertTrue(registration.body().contains("taskProviderApiUriPath"), registration.body());

  }

  @Test
  @DisplayName("One bridge per configured Process-Engine-API adapter id serves the cockpit")
  public void oneBridgePerConfiguredAdapterId() {

    assertEquals(1, bridges.size());
    assertEquals(ADAPTER_ID, bridges.getFirst().adapterId());
    assertEquals(PeaAdapter.ADAPTER_TYPE, bridges.getFirst().adapterType());

  }

}
