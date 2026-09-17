package io.vanillabp.cockpit.pea.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Consumer;

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

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * The Business Cockpit on the Process-Engine-API, from the delivery of a user task to the
 * request the cockpit server receives.
 * <p>
 * Nothing on that way is faked but the cockpit server and the engine. The workflow is started
 * through VanillaBP. The engine delivers a user task to the ADAPTER's own subscription, and the
 * adapter hands the delivery to the observer bean this extension contributes. The application's
 * details provider runs there and then, the finished report is written into the application's
 * outbox, and what arrives at the server is asserted.
 * <p>
 * What the engine says about a task is what an in-memory engine says, which is little: no
 * assignee, no process instance id, no reason. That is deliberate here. What the cockpit makes of
 * a generous meta map is asserted in the unit tests of the neutral module, and what only a booted
 * application shows is that a delivery reaches this extension at all.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class PeaCockpitTest {

  private static final String ADAPTER_ID = "pea";

  /**
   * How often the application repeats a transaction which read a conflict. Two would do for the
   * one other writer there is; the third is there so that a repetition which itself meets the
   * next report does not end the test.
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

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();
    engine.clearTaskRecordings();

  }

  /**
   * Changes one case and reports the change, the way an application whose workflow aggregate
   * carries a version attribute has to do it: in a transaction which is repeated where somebody
   * else wrote the same case in between.
   * <p>
   * That somebody is the Business Cockpit itself. Its details provider reads the case while a
   * report is built and writes it back when that transaction commits, so the two transactions
   * overlap whenever an application changes a case a delivery is being reported for. Without the
   * version attribute the later of the two writers wins silently; with it, one of them reads a
   * conflict and repeats.
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
   * A report carrying the older value has two possible causes and they need different work: the
   * report read the case too early, or the case itself lost the change. So a failure names what
   * the case carries now as well - see the version attribute of {@link TestAggregate}.
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
                .of("id", String.valueOf(aggregate.getId())));

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
    assertTrue(
        userTask
            .body()
            .contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("\"candidateGroups\":[\"drivers\"]"), userTask.body());
    // the BPMN name is what the cockpit falls back to when nothing else produced a title
    assertTrue(userTask.body().contains("Approve the ride"), userTask.body());

    // The customer in that body is what the details provider read off the case, so the report
    // shows that the provider ran on the real aggregate. Whether the case KEEPS what that
    // provider wrote into it is not asserted, on purpose. The write rides the transaction which
    // builds the report and writes the entry, and another writer of the same case can refuse
    // that commit through the version attribute. Entry and write are gone together then, so a
    // test waiting for that write waits on something it does not control.

  }

  @Test
  @DisplayName("A task the engine delivers again reaches the cockpit again")
  public void aRepeatedDeliveryReachesTheCockpitAgain() {

    final var aggregate = aStartedWorkflow("Rita");
    aDeliveredUserTask(aggregate, "task-6");
    CockpitServer.awaitRequest("/usertask/created");
    CockpitServer.forgetRequests();

    // the engine repeats a delivery whenever something about the task changed: its assignee, its
    // candidates or its data. The cockpit is told again. WHICH of the two it is told, created or
    // updated, is decided by the reason the engine names. The in-memory engine this test runs
    // against names none. It delivers with a meta map of one entry and no reason, so a repeated
    // delivery arrives here as a second creation. That the kind follows the reason is asserted in
    // the unit tests of the neutral module, and prompt 230 WP2 carries the change which would let
    // this engine drive it.
    aDeliveredUserTask(aggregate, "task-6");

    final var again = CockpitServer.awaitRequest("/usertask/created");
    assertTrue(again.body().contains("\"customer\":\"Rita\""), again.body());

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

    changeTheCase(
        aggregate.getId(),
        loaded -> {
          loaded.setCustomer("Dora the second");
          workflowService.businessCockpit().aggregateChanged(loaded);
        });

    // the report carries what the application wrote, not what the case said before it. The
    // report of the user task may still be under construction while this runs, and its details
    // provider holds the case over this transaction. The version attribute of TestAggregate is
    // what keeps that provider from writing the older reading back
    awaitReportCarrying(
        "/workflow/%s/updated".formatted(workflowIdOf(aggregate)),
        "\"customer\":\"Dora the second\"",
        aggregate);

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
