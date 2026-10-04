package io.vanillabp.cockpit.pea.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.cockpit.pea.PeaCockpitBridge;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks;
import io.vanillabp.cockpit.pea.PeaProcessVersions;
import io.vanillabp.cockpit.pea.PeaRecordedUserTasks;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.wiring.PeaTaskMeta;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * What this application still knows about a user task once the node which was given it is gone.
 * <p>
 * The memory of a delivery lives in one node and dies with it. VanillaBP writes every delivery it
 * processed into the application's own database, so a node which starts fresh reads there which
 * user tasks of a business case this application reported.
 * <p>
 * A restart is a fresh memory over the same database. Quarkus boots one application per test
 * class, so the fresh node here is a bridge built the way the producer builds one, with a memory
 * holding nothing and the delivery log the running application really writes to. Everything the
 * fresh bridge answers it read out of that database. The Spring Boot half of this repository
 * boots a second application context for the same question.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class RestartedNodeTest {

  private static final String ADAPTER_ID = "pea";

  private static final String MODULE_ID = "pea-cockpit";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = TestApplication.forTestClass(RestartedNodeTest.class);

  @Inject
  TestWorkflowService workflowService;

  @Inject
  InMemoryProcessEngine engine;

  @Inject
  PeaRecordedUserTasks recordedUserTasks;

  @Inject
  PeaDeployedProcessesRegistry deployedProcesses;

  @Inject
  PeaProcessVersions versions;

  @Inject
  UserTransaction transaction;

  @Inject
  WorkflowElection election;

  /**
   * The Business Cockpit half of a node which has just started: everything it knows it reads out
   * of the application's database.
   */
  private PeaCockpitBridge aFreshNode() {

    return new PeaCockpitBridge(
        ADAPTER_ID, deployedProcesses, new PeaDeliveredUserTasks(10), recordedUserTasks, versions, election, 10);

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
   * Makes the engine deliver a user task the application has a <code>&#64;WorkflowTask</code>
   * method for. VanillaBP writes a record while that method runs, and a delivery nobody
   * processes leaves none.
   */
  private void aDeliveredUserTask(
      final TestAggregate aggregate,
      final String taskId) {

    aDeliveredUserTask(aggregate, taskId, null);

  }

  /**
   * Like {@link #aDeliveredUserTask(TestAggregate, String)}, with the version tag the engine
   * writes into the meta map of the task, or without one.
   */
  private void aDeliveredUserTask(
      final TestAggregate aggregate,
      final String taskId,
      final String versionTag) {

    engine
        .deliverTask(
            taskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())),
            versionTag == null
                ? Map.of()
                : Map.of(PeaTaskMeta.PROCESS_VERSION_TAG, versionTag));
    CockpitServer.awaitRequest("/usertask/created", "\"userTaskId\":\"%s\"".formatted(taskId));

  }

  private List<UserTaskReference> openUserTasksOf(
      final PeaCockpitBridge node,
      final TestAggregate aggregate) {

    return node
        .userTasksOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String
                .valueOf(aggregate.getId()),
            List.of());

  }

  @Test
  @DisplayName("A node which starts fresh reads the user tasks of a case out of the database")
  public void aFreshNodeKnowsWhatWasReported() throws Exception {

    final var aggregate = aStartedWorkflow("Nora");
    aDeliveredUserTask(aggregate, "restart-1", TestWorkflowService.VERSION_TAG);

    final var open = openUserTasksOf(aFreshNode(), aggregate);

    assertEquals(
        List.of("restart-1"),
        open.stream().map(UserTaskReference::userTaskId).toList(),
        "the delivery was written down while the @WorkflowTask method ran");
    assertEquals(TestWorkflowService.TASK_DEFINITION, open.getFirst().taskDefinition());
    assertEquals(
        TestWorkflowService.BPMN_TASK_ID,
        open.getFirst().bpmnTaskId(),
        "the adapter wrote the BPMN element into the record, read out of the model it deployed");
    assertEquals(
        String.valueOf(aggregate.getId()),
        open.getFirst().workflowId(),
        "the in-memory engine names no process instance, so the case is shown under its aggregate");

    assertEquals(
        TestWorkflowService.VERSION_TAG,
        open.getFirst().processVersion(),
        "VanillaBP wrote the version tag of the delivery into the record");

    assertEquals(
        List.of(TestWorkflowService.VERSION_TAG),
        aFreshNode()
            .workflowsOfAggregate(
                MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String
                    .valueOf(aggregate.getId()))
            .stream()
            .map(WorkflowReference::processVersion)
            .toList(),
        "so the business case is found again as well, and a change of it is reported under its version");

  }

  @Test
  @DisplayName("A fresh node does not report a case whose record names no version")
  public void aFreshNodeDoesNotReportACaseWithoutAVersion() throws Exception {

    final var aggregate = aStartedWorkflow("Lea");
    aDeliveredUserTask(aggregate, "restart-4");

    final var node = aFreshNode();

    assertEquals(
        List.of("restart-4"),
        openUserTasksOf(node, aggregate).stream().map(UserTaskReference::userTaskId).toList(),
        "the task is known again");
    assertEquals(
        List.of(),
        node
            .workflowsOfAggregate(
                MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String
                    .valueOf(aggregate.getId())),
        "the engine filled no version tag, and a report without a version could empty the details the cockpit shows");

  }

  @Test
  @DisplayName("What a task shows is not in the database, so a fresh node cannot fill a report")
  public void aFreshNodeHasNoDetails() throws Exception {

    final var aggregate = aStartedWorkflow("Iris");
    aDeliveredUserTask(aggregate, "restart-2");

    final var node = aFreshNode();
    final var userTask = node
        .userTaskOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String
                .valueOf(aggregate.getId()),
            "restart-2")
        .orElseThrow();

    assertTrue(
        node.prefilledUserTaskDetails(userTask).isEmpty(),
        "a record carries identifiers and an outcome, and not one word the engine said");

  }

  @Test
  @DisplayName("A user task no @WorkflowTask method claims was never written down")
  public void aFreshNodeDoesNotKnowAnUnservedTask() throws Exception {

    final var aggregate = aStartedWorkflow("Pia");
    engine
        .deliverTask(
            "restart-3", TestWorkflowService.UNSERVED_TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())));
    CockpitServer.awaitRequest("/usertask/created", "\"userTaskId\":\"restart-3\"");

    assertEquals(
        List.of(),
        openUserTasksOf(aFreshNode(), aggregate),
        "a record carries the outcome of a delivery, and nobody processed this one");

  }

}
