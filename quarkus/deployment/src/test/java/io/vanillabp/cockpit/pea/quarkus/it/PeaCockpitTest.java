package io.vanillabp.cockpit.pea.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The Process-Engine-API half of the Business Cockpit inside a booted Quarkus application: the
 * extension is enabled, the engine delivers a user task to the ADAPTER's own subscription, the
 * adapter hands it to the observer bean this extension contributes, and what the application
 * enriched reaches the cockpit server. The bridge then answers what the cockpit reads back.
 * <p>
 * It runs the same way through as the Spring Boot test of this repository, and it exists because
 * a platform-neutral half being right says nothing about a platform's glue ever calling it.
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
              .addClass(TestWorkflowService.class)
              // the test class runs in the application's class loader, so the server it asks
              // about what arrived has to be reachable from there as well
              .addClass(CockpitServer.class))
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.rest.base-url", CockpitServer.baseUrl());

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

    final var workflow = CockpitServer.awaitRequest("/workflow/created");
    assertTrue(workflow.body().contains("\"customer\":\"Anna\""), workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created");
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
    CockpitServer.awaitRequest("/usertask/created");
    CockpitServer.forgetRequests();

    // the engine repeats a delivery whenever something about the task changed - its assignee,
    // its candidates or its data - and the cockpit is told again. WHICH of the two it is told,
    // created or updated, is decided by the reason the engine names, and the in-memory engine
    // this test runs against names none: it delivers with a meta map of one entry and no
    // reason, so a repeated delivery arrives here as a second creation. That the kind follows
    // the reason is asserted in the unit tests of the neutral module, and prompt 230 WP2
    // carries the change which would let this engine drive it.
    aDeliveredUserTask(aggregate, "task-6");

    final var again = CockpitServer.awaitRequest("/usertask/created");
    assertTrue(again.body().contains("\"customer\":\"Rita\""), again.body());

  }

  @Test
  @DisplayName("A task which is gone is reported as completed and is not readable any more")
  public void aTerminatedUserTaskIsReportedAsCompleted() throws Exception {

    CockpitServer.forgetRequests();

    final var aggregate = aStartedWorkflow("Bert");
    aDeliveredUserTask(aggregate, "task-2");
    CockpitServer.awaitRequest("/usertask/created");

    assertTrue(
        workflowService
            .businessCockpit()
            .getUserTask(aggregates.byId(aggregate.getId()), "task-2")
            .isPresent());

    engine
        .terminateTask(
            "task-2", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID,
            TaskInformation.COMPLETE);

    assertNotNull(CockpitServer.awaitRequest("/usertask/task-2/completed"));
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
    CockpitServer.awaitRequest("/workflow/created");
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
        .awaitRequest("/workflow/%s/updated".formatted(workflowIdOf(aggregate)));
    assertTrue(updated.body().contains("\"customer\":\"Cleo the second\""), updated.body());

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
