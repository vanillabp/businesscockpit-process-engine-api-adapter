package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.cockpit.extension.wiring.BusinessCockpitWiringService;
import io.vanillabp.cockpit.pea.PeaCockpitWiring;
import io.vanillabp.cockpit.pea.PeaWorkflowModels;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;

/**
 * What the extension takes out of VanillaBP's deployment pipeline, and what it says while it is
 * there. The message matters as much as the model: a developer who added this artifact expects
 * user tasks in the cockpit, and the startup is where they learn what still has to feed them.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaCockpitWiringTest {

  @Test
  @DisplayName("The extension joins a Process-Engine-API deployment and runs last")
  public void theExtensionJoinsAProcessEngineApiDeployment() {

    final var wiring = new PeaCockpitWiring(new PeaWorkflowModels());

    assertEquals(PeaBpmnModel.class, wiring.getModelType());
    assertEquals(PeaProcessingContext.class, wiring.getProcessContextType());
    assertEquals(BusinessCockpitWiringService.ORDER, wiring.getOrder());

  }

  @Test
  @DisplayName("What was wired is what the cockpit later reads a name and a BPMN element from")
  public void whatWasWiredIsRemembered() {

    final var models = new PeaWorkflowModels();
    final var wiring = new PeaCockpitWiring(models);

    wiring
        .wireBpmn(
            TestModels.MODULE_ID, "a-ride.bpmn", TestModels.BPMN_PROCESS_ID, TestModels.model(),
            new PeaProcessingContext(
                TestModels.MODULE_ID));

    final var process = models
        .of(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID)
        .orElseThrow();
    assertEquals("A taxi ride", process.name());
    assertEquals(
        "Approve the ride",
        process.userTasksByTaskDefinition().get(TestModels.USER_TASK_FORM).name());
    assertEquals(
        TestModels.USER_TASK_ELEMENT,
        process.userTasksByElementId().get(TestModels.USER_TASK_ELEMENT).bpmnTaskId());

  }

  @Test
  @DisplayName("The startup says once that nothing feeds the observer yet, and what to do about it")
  public void theStartupSaysWhatIsMissing(
      final CapturedOutput output) {

    final var wiring = new PeaCockpitWiring(new PeaWorkflowModels());

    wiring
        .startWorkflowProcessing(
            TestModels.MODULE_ID, new PeaProcessingContext(TestModels.MODULE_ID));
    wiring
        .startWorkflowProcessing(
            "another-module", new PeaProcessingContext("another-module"));

    final var said = output.getAll();
    assertTrue(
        said.contains("io.vanillabp.cockpit.pea.PeaUserTaskObserver"),
        () -> "the message names what an application has to feed: "
            + said);
    assertTrue(
        said.contains("GAPS.md"),
        () -> "the message names where the reason is written down: "
            + said);
    assertTrue(
        said.indexOf("delivers a user task to exactly ONE subscription") == said
            .lastIndexOf("delivers a user task to exactly ONE subscription"),
        "an application with several workflow modules hears it once");

  }

}
