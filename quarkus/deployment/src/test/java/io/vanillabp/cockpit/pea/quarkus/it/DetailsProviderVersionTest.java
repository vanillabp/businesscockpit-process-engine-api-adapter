package io.vanillabp.cockpit.pea.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.wiring.PeaTaskMeta;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Which details provider runs on this BPMS, and which one never does, inside a booted Quarkus
 * application. It is the twin of the Spring Boot test of the same name, and it exists for the
 * same reason every twin here exists: the two platforms wire the handlers of an application in
 * ways of their own.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class DetailsProviderVersionTest {

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
  InMemoryProcessEngine engine;

  @Inject
  UserTransaction transaction;

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();
    engine.clearTaskRecordings();

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
  public void theProviderRunsForTheVersionTagTheEngineFills() throws Exception {

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
  public void aDeliveryWithoutAVersionIsReportedWithoutTheProvider() throws Exception {

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
  public void aDeliveryOfAnotherVersionReachesNoProvider() throws Exception {

    final var aggregate = aStartedWorkflow("Timo");

    aDeliveredFareTask(aggregate, "fare-3", "ride-2026-08");

    final var userTask = CockpitServer.awaitRequest("/usertask/created", "fare-3");
    assertTrue(userTask.body().contains("ride-2026-08"), userTask.body());
    assertFalse(userTask.body().contains(TestWorkflowService.FARE_DETAIL), userTask.body());

  }

  @Test
  @DisplayName("The provider of a task without a version serves every delivery")
  public void aProviderWithoutAVersionServesEveryDelivery() throws Exception {

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
