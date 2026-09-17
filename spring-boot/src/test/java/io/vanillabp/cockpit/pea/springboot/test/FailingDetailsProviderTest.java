package io.vanillabp.cockpit.pea.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
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
import io.vanillabp.cockpit.pea.PeaCockpitObserver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.observation.PeaUserTaskObserverFailure;

/**
 * What a details provider which throws costs, now that it throws while the engine's delivery
 * thread waits.
 * <p>
 * The cockpit builds the report of an event inside the call the BPMS makes, so the application's
 * provider runs there. This half used to swallow what the provider threw, because an observer of
 * the Process-Engine-API could not refuse a delivery. It can now: the adapter turns a failing
 * observer into a failing delivery, and an engine behind this API offers a failed delivery again.
 * Decision 11 in the repository's DECISIONS.md holds the reasoning, and this test holds the
 * behaviour.
 * <p>
 * The two halves of it are told apart on purpose. A delivery is repeated and a termination is not,
 * so a report lost at the end of a task is lost for good.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class FailingDetailsProviderTest {

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

  /**
   * A provider left broken would break every test which runs after this class, because the
   * switch is a static of the application under test.
   */
  @AfterEach
  public void leaveAWorkingProvider() {

    TestWorkflowService.APPROVALS_TO_FAIL.set(0);

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

  private void aDeliveredUserTask(
      final TestAggregate aggregate,
      final String taskId) {

    engine
        .deliverTask(
            taskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())));

  }

  @Test
  @DisplayName("A details provider which fails lets the delivery fail, and names what broke")
  public void aFailingDetailsProviderFailsTheDelivery() {

    final var aggregate = aStartedWorkflow("Rosa");
    TestWorkflowService.APPROVALS_TO_FAIL.set(1);

    // the engine hands a failed delivery back to whoever delivered, which is what an engine
    // behind this API sees as well
    final var failure = assertThrows(
        PeaUserTaskObserverFailure.class,
        () -> aDeliveredUserTask(aggregate, "broken-1"));

    assertTrue(
        failure.getMessage().contains(PeaCockpitObserver.class.getName()),
        "the message has to name the cockpit's observer as the one which failed: "
            + failure.getMessage());
    assertTrue(failure.getMessage().contains("broken-1"), failure.getMessage());
    assertTrue(
        saysWhatTheProviderThrew(failure),
        "what the application's provider threw has to be somewhere in the chain of causes");

    assertEquals(
        List.of("broken-1"),
        TestWorkflowService.SERVED_NOTIFICATIONS,
        "the application keeps its notification: the failure goes out after it");

    // the business case is reported before the task is, in a transaction of its own, so it
    // survives the failure of the task's own report
    CockpitServer.awaitRequest("/workflow/created");
    CockpitServer.awaitQuiet();
    assertTrue(
        CockpitServer.matching("/usertask/created").isEmpty(),
        "the report and its outbox entry are written in one transaction, so a provider which "
            + "throws leaves no entry");

  }

  @Test
  @DisplayName("The delivery the engine repeats reports the task, and the case stays reported once")
  public void theRepeatedDeliveryReportsTheTask() {

    final var aggregate = aStartedWorkflow("Sami");
    TestWorkflowService.APPROVALS_TO_FAIL.set(1);
    assertThrows(
        PeaUserTaskObserverFailure.class,
        () -> aDeliveredUserTask(aggregate, "broken-2"));
    CockpitServer.awaitRequest("/workflow/created");

    // what an engine behind this API does with a failed delivery: it offers the task again
    aDeliveredUserTask(aggregate, "broken-2");

    final var userTask = CockpitServer.awaitRequest("/usertask/created");
    assertTrue(userTask.body().contains("\"customer\":\"Sami\""), userTask.body());

    CockpitServer.awaitQuiet();
    assertEquals(
        1,
        CockpitServer.matching("/usertask/created").size(),
        "the failed attempt wrote no entry, so the repetition is the only report of this task");
    assertEquals(
        1,
        CockpitServer.matching("/workflow/created").size(),
        "the node marks a case as reported once its entry is in, so the repeated delivery finds "
            + "the case reported");
    assertEquals(
        List.of("broken-2"),
        TestWorkflowService.SERVED_NOTIFICATIONS,
        "VanillaBP knows the repeated delivery by its task id, so the application hears about "
            + "the task once");

  }

  @Test
  @DisplayName("A details provider which fails on the end of a task lets the termination fail")
  public void aFailingDetailsProviderFailsTheTermination() {

    final var aggregate = aStartedWorkflow("Tilda");
    aDeliveredUserTask(aggregate, "broken-3");
    CockpitServer.awaitRequest("/usertask/created");
    CockpitServer.forgetRequests();

    TestWorkflowService.APPROVALS_TO_FAIL.set(1);
    assertThrows(
        PeaUserTaskObserverFailure.class,
        () -> engine
            .terminateTask(
                "broken-3", TestWorkflowService.TASK_DEFINITION,
                TestWorkflowService.BPMN_PROCESS_ID, TaskInformation.COMPLETE));

    CockpitServer.awaitQuiet();
    assertTrue(
        CockpitServer.matching("/usertask/broken-3/completed").isEmpty(),
        "the end of the task is not reported, and this BPMS offers no termination a second time");

  }

  /**
   * Whether the failure which reached the engine still carries what the application's details
   * provider threw. How many wrappers there are between the two is nobody's promise, so the whole
   * chain is read.
   */
  private static boolean saysWhatTheProviderThrew(
      final Throwable failure) {

    for (var cause = failure; cause != null; cause = cause.getCause()) {
      final var message = cause.getMessage();
      if ((message != null) && message.contains(TestWorkflowService.PROVIDER_BROKE)) {
        return true;
      }
    }
    return false;

  }

}
