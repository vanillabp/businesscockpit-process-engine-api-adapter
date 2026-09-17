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
import io.vanillabp.pea.wiring.PeaTaskMeta;

/**
 * Which details provider runs on this BPMS, and which one never does.
 * <p>
 * A details provider may name the versions of the BPMN process it serves, and VanillaBP picks the
 * method by the version the BPMS reported with the event. The Process-Engine-API reports one
 * thing and only sometimes: the version tag an engine wrote into the meta map of the delivered
 * task. So a method named for that tag runs, and a method named for anything else waits for a
 * version which never arrives.
 * <p>
 * The second case is the one worth a test, because nothing about it is loud. No matching method
 * is a legal answer for a details provider: the cockpit sends the prefilled details and the task
 * shows up without the enrichment the application wrote. Entry 12 in the repository's GAPS.md
 * says what that costs, and the test below is what keeps the entry honest.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class DetailsProviderVersionTest {

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
   * Delivers the user task whose provider names a version.
   *
   * @param aggregate The business case the task belongs to
   * @param taskId The engine's id of the task
   * @param versionTag The version tag the engine writes into the meta map, or <code>null</code>
   *          for an engine which keeps none
   */
  private void aDeliveredFareTask(
      final TestAggregate aggregate,
      final String taskId,
      final String versionTag) {

    engine
        .deliverTask(
            taskId, TestWorkflowService.VERSIONED_TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())),
            versionTag == null
                ? Map.of()
                : Map.of(PeaTaskMeta.PROCESS_VERSION_TAG, versionTag));

  }

  @Test
  @DisplayName("A provider named for the version tag the engine fills runs for that delivery")
  public void theProviderRunsForTheVersionTagTheEngineFills() {

    final var aggregate = aStartedWorkflow("Ronja");

    aDeliveredFareTask(aggregate, "fare-1", TestWorkflowService.VERSION_TAG);

    final var userTask = CockpitServer.awaitRequest("/usertask/created", "fare-1");
    assertTrue(
        userTask.body().contains("\"fare\":\"%s\"".formatted(TestWorkflowService.FARE_DETAIL)),
        userTask.body());
    // the version travelled with the report, which is what let VanillaBP pick the method
    assertTrue(userTask.body().contains(TestWorkflowService.VERSION_TAG), userTask.body());

  }

  @Test
  @DisplayName("A delivery without a version tag is reported without the provider's enrichment")
  public void aDeliveryWithoutAVersionIsReportedWithoutTheProvider() {

    final var aggregate = aStartedWorkflow("Silke");

    aDeliveredFareTask(aggregate, "fare-2", null);

    final var userTask = CockpitServer.awaitRequest("/usertask/created", "fare-2");
    // the report arrives, with what the engine and the deployed model say about the task
    assertTrue(
        userTask
            .body()
            .contains(
                "\"taskDefinition\":\"%s\""
                    .formatted(TestWorkflowService.VERSIONED_TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("Pay the fare"), userTask.body());
    // and without one word of the provider, because no method serves a delivery naming no
    // version. Nothing anywhere says so, which is the whole point of entry 12 in GAPS.md
    assertFalse(userTask.body().contains(TestWorkflowService.FARE_DETAIL), userTask.body());

  }

  @Test
  @DisplayName("A delivery of another version reaches no provider either")
  public void aDeliveryOfAnotherVersionReachesNoProvider() {

    final var aggregate = aStartedWorkflow("Timo");

    aDeliveredFareTask(aggregate, "fare-3", "ride-2026-08");

    final var userTask = CockpitServer.awaitRequest("/usertask/created", "fare-3");
    assertTrue(userTask.body().contains("ride-2026-08"), userTask.body());
    assertFalse(userTask.body().contains(TestWorkflowService.FARE_DETAIL), userTask.body());

  }

  @Test
  @DisplayName("The provider of a task without a version serves every delivery")
  public void aProviderWithoutAVersionServesEveryDelivery() {

    final var aggregate = aStartedWorkflow("Udo");

    engine
        .deliverTask(
            "approve-1", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_PROCESS_ID, Map
                .of("id", String.valueOf(aggregate.getId())),
            Map.of());

    final var userTask = CockpitServer.awaitRequest("/usertask/created", "approve-1");
    assertTrue(userTask.body().contains("\"customer\":\"Udo\""), userTask.body());

  }

}
