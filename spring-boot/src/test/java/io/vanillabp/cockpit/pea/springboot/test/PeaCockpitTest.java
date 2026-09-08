package io.vanillabp.cockpit.pea.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * The Business Cockpit on the Process-Engine-API, from the delivery of a user task to the
 * request the cockpit server receives.
 * <p>
 * Nothing on that way is faked but the cockpit server and the engine: the workflow is started
 * through VanillaBP, the engine delivers a user task to the ADAPTER's own subscription, the
 * adapter hands the delivery to the observer bean this extension contributes, the entry is
 * written into the application's outbox, dispatched afterwards, enriched by the application's
 * details provider, and what arrives at the server is asserted.
 * <p>
 * What the engine says about a task is what an in-memory engine says, which is little: no
 * assignee, no process instance id, no reason. That is deliberate here - what the cockpit makes
 * of a generous meta map is asserted in the unit tests of the neutral module, and what only a
 * booted application shows is that a delivery reaches this extension at all.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class PeaCockpitTest {

  private static final String ADAPTER_ID = "pea";

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

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();
    engine.clearTaskRecordings();

  }

  private TestAggregate aStartedWorkflow(
      final String customer) {

    return transactions
        .execute(status -> {
          final var aggregate = new TestAggregate();
          aggregate.setCustomer(customer);
          return workflowService.processes().startWorkflow(aggregate);
        });

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
                .of("id", String.valueOf(aggregate.getId()), "passenger", "Anna"));

  }

  @Test
  @DisplayName("A delivered user task and its business case reach the cockpit, enriched by the application")
  public void aDeliveredUserTaskReachesTheCockpit() {

    final var aggregate = aStartedWorkflow("Anna");

    aDeliveredUserTask(aggregate, "task-1");

    final var workflow = CockpitServer.awaitRequest("/workflow/created");
    assertTrue(workflow.body().contains("\"customer\":\"Anna\""), workflow.body());
    assertTrue(workflow.body().contains(TestWorkflowService.BPMN_PROCESS_ID), workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created");
    assertTrue(userTask.body().contains("\"customer\":\"Anna\""), userTask.body());
    assertTrue(userTask.body().contains("\"event\":\"CREATED\""), userTask.body());
    // the payload is what the SUBSCRIPTION asked the engine for, and this one asks for the
    // workflow aggregate's id alone: no @WorkflowTask method of the application claims this
    // user task, so nothing named another variable. GAPS.md, entry 4, says what that costs
    assertTrue(userTask.body().contains("\"passenger\":\"null\""), userTask.body());
    assertTrue(
        userTask
            .body()
            .contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("\"candidateGroups\":[\"drivers\"]"), userTask.body());
    // the BPMN name is what the cockpit falls back to when nothing else produced a title
    assertTrue(userTask.body().contains("Approve the ride"), userTask.body());

    // the details provider ran on the real aggregate and its change was saved
    assertEquals(
        TestWorkflowService.APPROVE_NOTE,
        aggregates.findById(aggregate.getId()).orElseThrow().getNote());

  }

  @Test
  @DisplayName("A task which is gone is reported as completed")
  public void aTerminatedUserTaskIsReportedAsCompleted() {

    final var aggregate = aStartedWorkflow("Cleo");
    aDeliveredUserTask(aggregate, "task-3");
    CockpitServer.awaitRequest("/usertask/created");

    engine
        .terminateTask(
            "task-3", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID,
            TaskInformation.COMPLETE);

    assertNotNull(CockpitServer.awaitRequest("/usertask/task-3/completed"));

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates the business case it knows")
  public void aggregateChangedUpdatesTheWorkflow() {

    final var aggregate = aStartedWorkflow("Dora");
    aDeliveredUserTask(aggregate, "task-4");
    CockpitServer.awaitRequest("/workflow/created");
    CockpitServer.forgetRequests();

    transactions
        .executeWithoutResult(status -> {
          final var loaded = aggregates.findById(aggregate.getId()).orElseThrow();
          loaded.setCustomer("Dora the second");
          aggregates.save(loaded);
          workflowService.businessCockpit().aggregateChanged(loaded);
        });

    final var updated = CockpitServer
        .awaitRequest("/workflow/%s/updated".formatted(workflowIdOf(aggregate)));
    assertTrue(updated.body().contains("\"customer\":\"Dora the second\""), updated.body());

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
