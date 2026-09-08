package io.vanillabp.cockpit.pea.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
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

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.pea.PeaTaskMeta;
import io.vanillabp.cockpit.pea.PeaUserTaskObservation;
import io.vanillabp.cockpit.pea.PeaUserTaskObserver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaAdapter;

/**
 * The Business Cockpit on the Process-Engine-API, from the delivery of a user task to the
 * request the cockpit server receives.
 * <p>
 * Nothing on that way is faked but the cockpit server and the delivery itself: the workflow is
 * started through VanillaBP, the observer is handed a task the way the Process-Engine-API
 * adapter would hand it over once it passes its subscriptions on (see GAPS.md, entry 1), the
 * entry is written into the application's outbox, dispatched afterwards, enriched by the
 * application's details provider, and what arrives at the server is asserted.
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
  private PeaUserTaskObserver observer;

  @Autowired
  private ObjectProvider<BusinessCockpitBpmsBridge> bridges;

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();

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

  private String workflowIdOf(
      final TestAggregate aggregate) {

    return "instance-of-%s".formatted(aggregate.getId());

  }

  /**
   * What the Process-Engine-API adapter would hand over: the identifiers it resolved while
   * routing the delivery, and the engine's own words about the task.
   */
  private PeaUserTaskObservation aDeliveredUserTask(
      final TestAggregate aggregate,
      final String taskId,
      final String reason) {

    final var meta = new LinkedHashMap<String, String>();
    meta.put(CommonRestrictions.PROCESS_INSTANCE_ID, workflowIdOf(aggregate));
    meta.put(PeaTaskMeta.BPMN_TASK_ID, TestWorkflowService.BPMN_TASK_ID);
    meta.put(PeaTaskMeta.ASSIGNEE, "james");
    return new PeaUserTaskObservation(
        ADAPTER_ID, TestApplication.MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.TASK_DEFINITION, String
            .valueOf(
                aggregate.getId()), new TaskInformation(taskId, meta).withReason(reason), Map.of("passenger", "Anna"));

  }

  @Test
  @DisplayName("A delivered user task and its business case reach the cockpit, enriched by the application")
  public void aDeliveredUserTaskReachesTheCockpit() {

    final var aggregate = aStartedWorkflow("Anna");

    observer
        .userTaskDelivered(aDeliveredUserTask(aggregate, "task-1", TaskInformation.CREATE));

    final var workflow = CockpitServer.awaitRequest("/workflow/created");
    assertTrue(workflow.body().contains("\"customer\":\"Anna\""), workflow.body());
    assertTrue(workflow.body().contains(TestWorkflowService.BPMN_PROCESS_ID), workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created");
    assertTrue(userTask.body().contains("\"customer\":\"Anna\""), userTask.body());
    assertTrue(userTask.body().contains("\"event\":\"CREATED\""), userTask.body());
    assertTrue(userTask.body().contains("\"passenger\":\"Anna\""), userTask.body());
    assertTrue(
        userTask
            .body()
            .contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("\"assignee\":\"james\""), userTask.body());
    assertTrue(userTask.body().contains("\"candidateGroups\":[\"drivers\"]"), userTask.body());
    // the BPMN name is what the cockpit falls back to when nothing else produced a title
    assertTrue(userTask.body().contains("Approve the ride"), userTask.body());

    // the details provider ran on the real aggregate and its change was saved
    assertEquals(
        TestWorkflowService.APPROVE_NOTE,
        aggregates.findById(aggregate.getId()).orElseThrow().getNote());

  }

  @Test
  @DisplayName("A task the engine delivers again because it changed is reported as an update")
  public void aRedeliveredUserTaskIsReportedAsAnUpdate() {

    final var aggregate = aStartedWorkflow("Bert");
    observer
        .userTaskDelivered(aDeliveredUserTask(aggregate, "task-2", TaskInformation.CREATE));
    CockpitServer.awaitRequest("/usertask/created");

    observer
        .userTaskDelivered(aDeliveredUserTask(aggregate, "task-2", TaskInformation.ASSIGN));

    final var updated = CockpitServer.awaitRequest("/usertask/task-2/updated");
    assertTrue(updated.body().contains("\"assignee\":\"james\""), updated.body());

  }

  @Test
  @DisplayName("A task which is gone is reported as completed")
  public void aTerminatedUserTaskIsReportedAsCompleted() {

    final var aggregate = aStartedWorkflow("Cleo");
    observer
        .userTaskDelivered(aDeliveredUserTask(aggregate, "task-3", TaskInformation.CREATE));
    CockpitServer.awaitRequest("/usertask/created");

    observer
        .userTaskTerminated(
            new PeaUserTaskObservation(
                ADAPTER_ID, TestApplication.MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.TASK_DEFINITION, null, new TaskInformation(
                    "task-3", Map.of()).withReason(TaskInformation.COMPLETE), Map.of()));

    assertNotNull(CockpitServer.awaitRequest("/usertask/task-3/completed"));

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates the business case it knows")
  public void aggregateChangedUpdatesTheWorkflow() {

    final var aggregate = aStartedWorkflow("Dora");
    observer
        .userTaskDelivered(aDeliveredUserTask(aggregate, "task-4", TaskInformation.CREATE));
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
    observer
        .userTaskDelivered(aDeliveredUserTask(aggregate, "task-5", TaskInformation.CREATE));

    final var userTask = transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(aggregates.findById(aggregate.getId()).orElseThrow(), "task-5"));
    assertTrue(userTask.isPresent());
    assertEquals("task-5", userTask.get().getId());
    assertEquals(TestWorkflowService.TASK_DEFINITION, userTask.get().getTaskDefinition());

    observer
        .userTaskTerminated(
            new PeaUserTaskObservation(
                ADAPTER_ID, TestApplication.MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.TASK_DEFINITION, null, new TaskInformation(
                    "task-5", Map.of()), Map.of()));

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
