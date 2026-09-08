package io.vanillabp.cockpit.pea.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
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
import io.vanillabp.cockpit.pea.PeaTaskMeta;
import io.vanillabp.cockpit.pea.PeaUserTaskObservation;
import io.vanillabp.cockpit.pea.PeaUserTaskObserver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaAdapter;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The Process-Engine-API half of the Business Cockpit inside a booted Quarkus application: the
 * extension is enabled, a delivered user task reaches the cockpit server with the details the
 * application provides, and the bridge answers what the cockpit reads back.
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
  PeaUserTaskObserver observer;

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

  private static String workflowIdOf(
      final TestAggregate aggregate) {

    return "instance-of-%s".formatted(aggregate.getId());

  }

  /**
   * What the Process-Engine-API adapter would hand over: the identifiers it resolved while
   * routing the delivery, and the engine's own words about the task.
   */
  private static PeaUserTaskObservation aDeliveredUserTask(
      final TestAggregate aggregate,
      final String taskId,
      final String reason) {

    final var meta = new LinkedHashMap<String, String>();
    meta.put(CommonRestrictions.PROCESS_INSTANCE_ID, workflowIdOf(aggregate));
    meta.put(PeaTaskMeta.BPMN_TASK_ID, TestWorkflowService.BPMN_TASK_ID);
    meta.put(PeaTaskMeta.ASSIGNEE, "james");
    return new PeaUserTaskObservation(
        ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.TASK_DEFINITION, String
            .valueOf(
                aggregate.getId()), new TaskInformation(taskId, meta).withReason(reason), Map.of("passenger", "Anna"));

  }

  @Test
  @DisplayName("A delivered user task and its business case reach the cockpit, enriched by the application")
  public void aDeliveredUserTaskReachesTheCockpit() throws Exception {

    CockpitServer.forgetRequests();

    final var aggregate = aStartedWorkflow("Anna");
    observer.userTaskDelivered(aDeliveredUserTask(aggregate, "task-1", TaskInformation.CREATE));

    final var workflow = CockpitServer.awaitRequest("/workflow/created");
    assertTrue(workflow.body().contains("\"customer\":\"Anna\""), workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created");
    assertTrue(userTask.body().contains("\"customer\":\"Anna\""), userTask.body());
    assertTrue(userTask.body().contains("\"passenger\":\"Anna\""), userTask.body());
    assertTrue(userTask.body().contains("\"assignee\":\"james\""), userTask.body());
    assertTrue(userTask.body().contains("Approve the ride"), userTask.body());

    assertEquals(
        TestWorkflowService.APPROVE_NOTE, aggregates.byId(aggregate.getId()).getNote());

  }

  @Test
  @DisplayName("A task the engine delivers again because it changed is reported as an update")
  public void aRedeliveredUserTaskIsReportedAsAnUpdate() throws Exception {

    CockpitServer.forgetRequests();

    final var aggregate = aStartedWorkflow("Rita");
    observer.userTaskDelivered(aDeliveredUserTask(aggregate, "task-4", TaskInformation.CREATE));
    final var created = CockpitServer.awaitRequest("/usertask/created");
    assertTrue(
        created
            .body()
            .contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        created.body());

    observer.userTaskDelivered(aDeliveredUserTask(aggregate, "task-4", TaskInformation.ASSIGN));

    final var updated = CockpitServer.awaitRequest("/usertask/task-4/updated");
    assertTrue(updated.body().contains("\"assignee\":\"james\""), updated.body());
    assertTrue(updated.body().contains("\"candidateGroups\":[\"drivers\"]"), updated.body());

  }

  @Test
  @DisplayName("A task which is gone is reported as completed and is not readable any more")
  public void aTerminatedUserTaskIsReportedAsCompleted() throws Exception {

    CockpitServer.forgetRequests();

    final var aggregate = aStartedWorkflow("Bert");
    observer.userTaskDelivered(aDeliveredUserTask(aggregate, "task-2", TaskInformation.CREATE));
    CockpitServer.awaitRequest("/usertask/created");

    assertTrue(
        workflowService
            .businessCockpit()
            .getUserTask(aggregates.byId(aggregate.getId()), "task-2")
            .isPresent());

    observer
        .userTaskTerminated(
            new PeaUserTaskObservation(
                ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.TASK_DEFINITION, null, new TaskInformation(
                    "task-2", Map.of()).withReason(TaskInformation.COMPLETE), Map.of()));

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
    observer.userTaskDelivered(aDeliveredUserTask(aggregate, "task-3", TaskInformation.CREATE));
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
