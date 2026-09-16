package io.vanillabp.cockpit.pea.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * What the Business Cockpit hears about a user task no <code>&#64;WorkflowTask</code> method of
 * the application claims.
 * <p>
 * The taxi ride of this test module has two user tasks. One is served: the application has a
 * method for it and is notified when the engine delivers it. The other is not: it is worked on in
 * a task list and nothing in the application is called for it. Everything else about the two is
 * the same, the details provider included, so a difference in what the cockpit is told can only
 * come from the missing method.
 * <p>
 * The measurement was asked for by story 1299, which weighs the memory of this extension
 * (decision 3 in the repository's DECISIONS.md) against the delivery log of the platform. That
 * log is written where a handler was invoked, so it holds the served task and not the other one.
 * What this test says is whether the cockpit half sees more than the log could ever answer.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class UnservedUserTaskTest {

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
   * Delivers the user task nothing claims, the way an engine does when a workflow reaches it.
   */
  private void anUnservedUserTask(
      final TestAggregate aggregate,
      final String taskId) {

    engine
        .deliverTask(
            taskId, TestWorkflowService.UNSERVED_TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())));

  }

  @Test
  @DisplayName("Both user tasks are subscribed, the one with a method and the one without")
  public void bothUserTasksAreSubscribed() {

    final var subscribed = engine
        .getSubscriptions()
        .stream()
        .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
        .toList();

    assertTrue(
        subscribed
            .containsAll(
                List
                    .of(
                        TestWorkflowService.TASK_DEFINITION,
                        TestWorkflowService.UNSERVED_TASK_DEFINITION)),
        "expected a subscription per user task of the model but got: "
            + subscribed);

  }

  @Test
  @DisplayName("A user task no @WorkflowTask method claims reaches the cockpit like every other")
  public void anUnservedUserTaskReachesTheCockpit() {

    final var aggregate = aStartedWorkflow("Nora");

    anUnservedUserTask(aggregate, "unserved-1");

    // the business case appears with its first user task, and this one is the first
    final var workflow = CockpitServer.awaitRequest("/workflow/created");
    assertTrue(workflow.body().contains("\"customer\":\"Nora\""), workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created");
    // a first delivery, not a repetition of one the cockpit already knows
    assertTrue(userTask.body().contains("\"updated\":false"), userTask.body());
    assertTrue(
        userTask
            .body()
            .contains(
                "\"taskDefinition\":\"%s\""
                    .formatted(TestWorkflowService.UNSERVED_TASK_DEFINITION)),
        userTask.body());
    // the BPMN element id tells two user tasks apart which show the same form
    assertTrue(
        userTask.body().contains(TestWorkflowService.UNSERVED_BPMN_TASK_ID), userTask.body());
    // what the modeller wrote on the element, which is the title a task list shows
    assertTrue(userTask.body().contains("Inspect the car"), userTask.body());
    // the details provider of this task ran, on the real workflow aggregate
    assertTrue(userTask.body().contains("\"customer\":\"Nora\""), userTask.body());
    assertTrue(userTask.body().contains("\"candidateGroups\":[\"inspectors\"]"), userTask.body());

    assertEquals(
        List.of(),
        TestWorkflowService.SERVED_NOTIFICATIONS,
        "no method of the application claims this task, so nothing of the application ran");

  }

  @Test
  @DisplayName("The end of a user task nothing claims is reported too")
  public void theEndOfAnUnservedUserTaskIsReported() {

    final var aggregate = aStartedWorkflow("Olga");
    anUnservedUserTask(aggregate, "unserved-2");
    CockpitServer.awaitRequest("/usertask/created");

    engine
        .terminateTask(
            "unserved-2", TestWorkflowService.UNSERVED_TASK_DEFINITION,
            TestWorkflowService.BPMN_PROCESS_ID, TaskInformation.COMPLETE);

    assertNotNull(CockpitServer.awaitRequest("/usertask/unserved-2/completed"));

  }

  @Test
  @DisplayName("A user task nothing claims is readable through the bridge while this node holds it")
  public void anUnservedUserTaskIsReadableThroughTheBridge() {

    final var aggregate = aStartedWorkflow("Pia");
    anUnservedUserTask(aggregate, "unserved-3");
    CockpitServer.awaitRequest("/usertask/created");

    final var userTask = transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(aggregates.findById(aggregate.getId()).orElseThrow(), "unserved-3"));

    assertTrue(
        userTask.isPresent(),
        "the memory of this node answers for a task no @WorkflowTask method claims");
    assertEquals(
        TestWorkflowService.UNSERVED_TASK_DEFINITION, userTask.get().getTaskDefinition());

  }

  @Test
  @DisplayName("The served user task and the unserved one are told apart by the method alone")
  public void theServedUserTaskStillNotifiesTheApplication() {

    final var aggregate = aStartedWorkflow("Quirin");

    engine
        .deliverTask(
            "served-1", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())));

    final var userTask = CockpitServer.awaitRequest("/usertask/created");
    assertTrue(
        userTask
            .body()
            .contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());
    assertEquals(
        List.of("served-1"),
        TestWorkflowService.SERVED_NOTIFICATIONS,
        "this one the application does have a method for");

  }

}
