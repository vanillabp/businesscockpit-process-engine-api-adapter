package io.vanillabp.cockpit.pea.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.cockpit.pea.PeaRecordedUserTasks;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * What this application still knows about a user task once the node which was given it is gone.
 * <p>
 * The memory of a delivery lives in one node and dies with it. VanillaBP writes every delivery it
 * processed into the application's own database, so a node which starts fresh reads there which
 * tasks of a business case this application reported and which of them are over. The restart is
 * the real thing here: a second application context is booted on the same database, and
 * everything it knows it read out of that database.
 * <p>
 * What the fresh node does NOT get back is what the engine said about the task. That is entry 10
 * in the repository's GAPS.md, and it is why this test asks the bridge and not the cockpit
 * server: a report about a task whose details are gone is dropped while it is dispatched.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class RestartedNodeTest {

  private static final String ADAPTER_ID = "pea";

  private static final String MODULE_ID = TestApplication.MODULE_ID;

  /** How long the test gives VanillaBP to stamp the record of a completed task. */
  private static final Duration STAMP_TIMEOUT = Duration.ofSeconds(30);

  /**
   * A database of its own. VanillaBP's delivery records are not a JPA entity, so nothing drops
   * them between two test classes, while the workflow aggregates are created anew with every
   * context. Sharing the database would let this test's aggregate ids meet the records another
   * test class left behind.
   */
  private static final String DATABASE = "jdbc:h2:mem:pea-cockpit-restart;DB_CLOSE_DELAY=-1";

  @DynamicPropertySource
  static void aDatabaseOfItsOwn(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.cockpit.rest.base-url", CockpitServer::baseUrl);
    registry.add("spring.datasource.url", () -> DATABASE);

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
  private PeaRecordedUserTasks recordedUserTasks;

  /**
   * Waits until VanillaBP has written down that the task is closed.
   * <p>
   * The record is stamped once the completion reached the engine, which is after the report of
   * the end was written and therefore possibly after that report arrived at the cockpit server.
   * So the test waits for the thing it is about to assert on rather than for something which
   * usually happens first.
   */
  private void awaitTheRecordOfTheTaskBeingStamped(
      final TestAggregate aggregate,
      final String taskId) {

    final var giveUpAt = System.currentTimeMillis() + STAMP_TIMEOUT.toMillis();
    while (System.currentTimeMillis() < giveUpAt) {
      final var stillOpen = recordedUserTasks
          .openTaskOfAggregate(
              ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String
                  .valueOf(aggregate.getId()),
              taskId);
      if (stillOpen.isEmpty()) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for the record of "
            + taskId, e);
      }
    }
    throw new AssertionError(
        "VanillaBP did not write down within %s that user task '%s' is closed"
            .formatted(STAMP_TIMEOUT, taskId));

  }

  /**
   * Boots a second application context on the same database, which is what a restarted node is:
   * the tables are the ones which were there, and every memory of the process which wrote them
   * is gone.
   * <p>
   * The schema is not created a second time. A fresh context creating it would drop the workflow
   * aggregates this test started, and a restarted application does not do that either.
   */
  private ConfigurableApplicationContext aRestartedNode() {

    // as command line arguments rather than as default properties: the test's own
    // application.yaml wins over the latter, and the schema it asks for would drop the workflow
    // aggregates this test started
    return new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.jpa.hibernate.ddl-auto=none", "--spring.datasource.url="
                + DATABASE,
            "--vanillabp.cockpit.rest.base-url="
                + CockpitServer.baseUrl());

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
   * Makes the engine deliver a user task the application has a <code>&#64;WorkflowTask</code>
   * method for. VanillaBP writes a record while that method runs, and a delivery nobody
   * processes leaves none.
   */
  private void aDeliveredUserTask(
      final TestAggregate aggregate,
      final String taskId) {

    engine
        .deliverTask(
            taskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())));
    CockpitServer.awaitRequest("/usertask/created", "\"userTaskId\":\"%s\"".formatted(taskId));

  }

  /**
   * What the Business Cockpit half of the restarted node answers about a business case.
   */
  private static List<UserTaskReference> openUserTasksOf(
      final ConfigurableApplicationContext node,
      final TestAggregate aggregate) {

    return node
        .getBean(BusinessCockpitBpmsBridge.class)
        .userTasksOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String
                .valueOf(aggregate.getId()),
            List.of());

  }

  @Test
  @DisplayName("A node which starts fresh reads the user tasks of a case out of the database")
  public void aFreshNodeKnowsWhatWasReported() {

    final var aggregate = aStartedWorkflow("Nora");
    aDeliveredUserTask(aggregate, "restart-1");

    try (var restarted = aRestartedNode()) {
      final var open = openUserTasksOf(restarted, aggregate);

      assertEquals(
          List.of("restart-1"),
          open.stream().map(UserTaskReference::userTaskId).toList(),
          "the delivery was written down while the @WorkflowTask method ran");
      assertEquals(TestWorkflowService.TASK_DEFINITION, open.getFirst().taskDefinition());
      assertEquals(
          TestWorkflowService.BPMN_TASK_ID,
          open.getFirst().bpmnTaskId(),
          "the record names no BPMN element on this BPMS, so it comes from what the adapter deployed");
      assertEquals(
          String.valueOf(aggregate.getId()),
          open.getFirst().workflowId(),
          "and it names no workflow either, so the case is shown under its aggregate");

      assertFalse(
          restarted
              .getBean(BusinessCockpitBpmsBridge.class)
              .workflowsOfAggregate(
                  MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String
                      .valueOf(aggregate.getId()))
              .isEmpty(),
          "so the business case is found again as well, and a change of it is reported");
    }

  }

  @Test
  @DisplayName("A fresh node does not show a user task the application has finished")
  public void aFreshNodeKnowsHowATaskEnded() {

    final var aggregate = aStartedWorkflow("Iris");
    aDeliveredUserTask(aggregate, "restart-2");

    transactions
        .executeWithoutResult(
            status -> workflowService
                .processes()
                .completeUserTask(
                    aggregates.findById(aggregate.getId()).orElseThrow(), "restart-2"));
    CockpitServer.awaitRequest("/usertask/restart-2/completed");
    awaitTheRecordOfTheTaskBeingStamped(aggregate, "restart-2");

    try (var restarted = aRestartedNode()) {
      assertEquals(
          List.of(),
          openUserTasksOf(restarted, aggregate),
          "the record is stamped once the completion reached the engine, and a stamped record is not open");
    }

  }

}
