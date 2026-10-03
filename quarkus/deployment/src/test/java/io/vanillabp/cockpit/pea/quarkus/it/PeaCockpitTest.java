package io.vanillabp.cockpit.pea.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
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
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class PeaCockpitTest {

  private static final String ADAPTER_ID = "pea";

  private static final String MODULE_ID = "pea-cockpit";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(
          jar -> jar
              .addAsResource("business-cockpit.yaml", "application.yaml")
              .addAsResource("pea-cockpit/processes/taxi-ride.bpmn")
              .addAsResource(
                  "workflow-module-descriptor/workflow-module", "META-INF/workflow-module")
              .addClass(TestAggregate.class)
              .addClass(TestAggregatePersistence.class)
              .addClass(TestWorkflowService.class))
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.rest.base-url", CockpitServer.baseUrl())
      // a database of its own. VanillaBP's delivery records are not dropped between two test
      // classes, while the workflow aggregates of this application live in memory and start at
      // the first id with every boot. An open record another class left behind for the same
      // aggregate id would name a workflow of a case this test thinks has no open user task
      .overrideRuntimeConfigKey(
          "quarkus.datasource.jdbc.url", "jdbc:h2:mem:pea-cockpit-quarkus-cockpit;DB_CLOSE_DELAY=-1");

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

  private TestAggregate aStartedWorkflow(
      final String customer) throws Exception {

    transaction.begin();
    try {
      final var aggregate = new TestAggregate();
      aggregate.setCustomer(customer);
      final var started = workflowService.processes().startWorkflow(aggregate);
      transaction.commit();
      return started;
    } catch (final RuntimeException e) {
      transaction.rollback();
      throw e;
    }

  }

  /**
   * The identifier the cockpit shows a business case under. This engine names no process
   * instance, so it is the workflow aggregate's id - decision 6 in the repository's
   * DECISIONS.md.
   */
  private static String workflowIdOf(
      final TestAggregate aggregate) {

    return String.valueOf(aggregate.getId());

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
                .of("id", String.valueOf(aggregate.getId())));

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
  @DisplayName("A task the engine delivers again reaches the cockpit again")
  public void aRepeatedDeliveryReachesTheCockpitAgain() throws Exception {

    final var aggregate = aStartedWorkflow("Rita");
    aDeliveredUserTask(aggregate, "task-6");
    CockpitServer.awaitRequestOf("/usertask/created", "\"userTaskId\":\"task-6\"");
    CockpitServer.forgetRequests();

    // the engine repeats a delivery whenever something about the task changed - its assignee,
    // its candidates or its data - and the cockpit is told again. WHICH of the two it is told,
    // created or updated, is decided by the reason the engine names, and the in-memory engine
    // this test runs against names none: it delivers with a meta map of one entry and no
    // reason, so a repeated delivery arrives here as a second creation. That the kind follows
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
    aDeliveredUserTask(aggregate, "task-2");
    CockpitServer.awaitRequestOf("/usertask/created", "\"userTaskId\":\"task-2\"");

    assertTrue(
        workflowService
            .businessCockpit()
            .getUserTask(aggregates.byId(aggregate.getId()), "task-2")
            .isPresent());

    engine
        .terminateTask(
            "task-2", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID,
            TaskInformation.COMPLETE);

    assertNotNull(CockpitServer.awaitAnyRequest("/usertask/task-2/completed"));
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
    final var workflowId = awaitTheStartedWorkflowOf(aggregate);
    // an engine like the reference one, which names the instance it answered the start with in
    // every task it delivers. The in-memory engine names none unless it is told to
    engine
        .deliverTask(
            "task-7", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())),
            Map
                .of(CommonRestrictions.PROCESS_INSTANCE_ID, workflowId));
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
