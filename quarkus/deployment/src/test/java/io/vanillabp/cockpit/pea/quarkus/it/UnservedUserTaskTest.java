package io.vanillabp.cockpit.pea.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * What the Business Cockpit hears about a user task no <code>&#64;WorkflowTask</code> method of
 * the application claims, inside a booted Quarkus application.
 * <p>
 * It is the twin of the Spring Boot test of the same name. It exists because the two platforms
 * collect the observers of an application in ways of their own, and a platform-neutral half being
 * right says nothing about a platform's glue ever calling it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class UnservedUserTaskTest {

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
          "vanillabp.cockpit.rest.base-url", CockpitServer.baseUrl());

  @Inject
  TestWorkflowService workflowService;

  @Inject
  TestAggregatePersistence aggregates;

  @Inject
  InMemoryProcessEngine engine;

  @Inject
  UserTransaction transaction;

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();
    engine.clearTaskRecordings();
    TestWorkflowService.SERVED_NOTIFICATIONS.clear();

  }

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
  public void anUnservedUserTaskReachesTheCockpit() throws Exception {

    final var aggregate = aStartedWorkflow("Nora");

    anUnservedUserTask(aggregate, "unserved-1");

    final var workflow = CockpitServer
        .awaitRequest(
            "/workflow/created", "\"workflowId\":\"%s\"".formatted(aggregate.getId()));
    assertTrue(workflow.body().contains("\"customer\":\"Nora\""), workflow.body());

    final var userTask = CockpitServer
        .awaitRequest("/usertask/created", "\"userTaskId\":\"unserved-1\"");
    assertTrue(
        userTask
            .body()
            .contains(
                "\"taskDefinition\":\"%s\""
                    .formatted(TestWorkflowService.UNSERVED_TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("Inspect the car"), userTask.body());
    assertTrue(userTask.body().contains("\"candidateGroups\":[\"inspectors\"]"), userTask.body());

    assertEquals(
        List.of(),
        TestWorkflowService.SERVED_NOTIFICATIONS,
        "no method of the application claims this task, so nothing of the application ran");

  }

  @Test
  @DisplayName("The end of a user task nothing claims is reported too")
  public void theEndOfAnUnservedUserTaskIsReported() throws Exception {

    final var aggregate = aStartedWorkflow("Olga");
    anUnservedUserTask(aggregate, "unserved-2");
    CockpitServer.awaitAnyRequest("/usertask/created");

    assertTrue(
        workflowService
            .businessCockpit()
            .getUserTask(aggregates.byId(aggregate.getId()), "unserved-2")
            .isPresent(),
        "the memory of this node answers for a task no @WorkflowTask method claims");

    engine
        .terminateTask(
            "unserved-2", TestWorkflowService.UNSERVED_TASK_DEFINITION,
            TestWorkflowService.BPMN_PROCESS_ID, TaskInformation.COMPLETE);

    assertNotNull(CockpitServer.awaitAnyRequest("/usertask/unserved-2/completed"));

  }

  @Test
  @DisplayName("The served user task and the unserved one are told apart by the method alone")
  public void theServedUserTaskStillNotifiesTheApplication() throws Exception {

    final var aggregate = aStartedWorkflow("Quirin");

    engine
        .deliverTask(
            "served-1", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())));

    final var userTask = CockpitServer
        .awaitRequest("/usertask/created", "\"userTaskId\":\"served-1\"");
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
