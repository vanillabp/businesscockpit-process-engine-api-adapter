package io.vanillabp.cockpit.pea.springboot.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * The cockpit hears only about the BPMN processes a <code>&#64;WorkflowService</code> of the
 * application claims.
 * <p>
 * The file of the taxi ride carries three processes. The ride is claimed by the workflow service.
 * The car check is called by the ride and claimed by the same service as a secondary process. The
 * ride review is claimed by nobody: it only travels in the file, and the test configuration marks it
 * as served by somebody else. A fourth process is not deployed by this application at all. It
 * stands for a process of somebody else which runs on the same engine.
 * <p>
 * The engine of this test runs nothing by itself. A test delivers a task the way an engine does,
 * and it can only deliver to a subscription. So a task of a process nobody claims can reach the
 * cockpit only where it shows the same form as a task of a claimed process. That is what the review
 * and the foreign process do here, and it is the way such a task would arrive in production.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OnlyClaimedProcessesTest {

  /** A process this application never deployed, which an engine may run all the same. */
  private static final String FOREIGN_BPMN_PROCESS_ID = "SomebodyElsesRide";

  @DynamicPropertySource
  static void cockpitServer(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.cockpit.rest.base-url", CockpitServer::baseUrl);

  }

  @Autowired
  private TestWorkflowService workflowService;

  @Autowired
  private TransactionTemplate transactions;

  @Autowired
  private InMemoryProcessEngine engine;

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();
    engine.clearTaskRecordings();
    TestWorkflowService.SERVED_NOTIFICATIONS.clear();

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
   * Delivers a task which shows the form of the approval of the ride, for the given process.
   */
  private void anApprovalFormOf(
      final String bpmnProcessId,
      final TestAggregate aggregate,
      final String taskId) {

    // the id as text matches no start variable, so this engine names no instance, like one
    // which keeps no instance id in its tasks. The case stands under the aggregate's id
    engine
        .deliverTask(
            taskId, TestWorkflowService.TASK_DEFINITION, bpmnProcessId, Map
                .of("id", String.valueOf(aggregate.getId())));

  }

  @Test
  @DisplayName("The user tasks of a process nobody claims get no subscription")
  public void aProcessNobodyClaimsGetsNoSubscription() {

    final var subscribed = engine
        .getSubscriptions()
        .stream()
        .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
        .toList();

    assertFalse(
        subscribed.contains(TestWorkflowService.UNCLAIMED_TASK_DEFINITION),
        "only the review has this form, and nobody claims the review, but got: "
            + subscribed);
    assertTrue(
        subscribed.contains(TestWorkflowService.CALLED_TASK_DEFINITION),
        "the car check is claimed as a secondary process, but got: "
            + subscribed);

  }

  @Test
  @DisplayName("A task of a process deployed with the module but claimed by nobody is not reported")
  public void aTaskOfAProcessNobodyClaimsIsNotReported() {

    final var aggregate = aStartedWorkflow("Rita");

    anApprovalFormOf(TestWorkflowService.UNCLAIMED_BPMN_PROCESS_ID, aggregate, "review-1");
    // a task of the ride after it, so that the quiet below is not just a slow outbox
    anApprovalFormOf(TestWorkflowService.BPMN_PROCESS_ID, aggregate, "ride-1");
    CockpitServer.awaitRequestOf("/usertask/created", "\"userTaskId\":\"ride-1\"");
    CockpitServer.awaitQuiet();

    assertTrue(
        CockpitServer
            .matching("/usertask/created")
            .stream()
            .noneMatch(request -> request.body().contains("review-1")),
        "the review came with the application's file, but no @WorkflowService claims it");
    assertTrue(
        CockpitServer
            .matching("/workflow/created")
            .stream()
            .noneMatch(request -> request.body().contains(TestWorkflowService.UNCLAIMED_BPMN_PROCESS_ID)),
        "the review is no business case of this application");

  }

  @Test
  @DisplayName("A task of a process this application never deployed is not reported")
  public void aTaskOfAForeignProcessIsNotReported() {

    final var aggregate = aStartedWorkflow("Sven");

    anApprovalFormOf(FOREIGN_BPMN_PROCESS_ID, aggregate, "foreign-1");
    anApprovalFormOf(TestWorkflowService.BPMN_PROCESS_ID, aggregate, "ride-2");
    CockpitServer.awaitRequestOf("/usertask/created", "\"userTaskId\":\"ride-2\"");
    CockpitServer.awaitQuiet();

    assertTrue(
        CockpitServer
            .matching("/usertask/created")
            .stream()
            .noneMatch(request -> request.body().contains("foreign-1")),
        "somebody else's process is not reported, even with a form this application knows");
    assertTrue(
        CockpitServer
            .matching("/workflow/created")
            .stream()
            .noneMatch(request -> request.body().contains(FOREIGN_BPMN_PROCESS_ID)));

  }

  @Test
  @DisplayName("A task of a process called by the ride and claimed as a secondary process is reported")
  public void aTaskOfASecondaryProcessIsReported() {

    final var aggregate = aStartedWorkflow("Tom");

    // the id as text matches no start variable, so this engine names no instance, like one
    // which keeps no instance id in its tasks. The case stands under the aggregate's id
    engine
        .deliverTask(
            "check-1", TestWorkflowService.CALLED_TASK_DEFINITION, TestWorkflowService.CALLED_BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())));

    final var userTask = CockpitServer
        .awaitRequest("/usertask/created", "\"userTaskId\":\"check-1\"");
    assertTrue(
        userTask.body().contains("\"bpmnProcessId\":\"%s\"".formatted(TestWorkflowService.CALLED_BPMN_PROCESS_ID)),
        userTask.body());
    assertTrue(userTask.body().contains("Check the brakes"), userTask.body());

  }

}
