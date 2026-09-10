package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.cockpit.extension.wiring.BusinessCockpitWiringService;
import io.vanillabp.cockpit.pea.PeaCockpitWiring;
import io.vanillabp.cockpit.pea.PeaWorkflowModels;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;

/**
 * What the extension takes out of VanillaBP's deployment pipeline: the models it will later read
 * a name and a BPMN element from, because this BPMS has no repository API to ask afterwards.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaCockpitWiringTest {

  @Test
  @DisplayName("The extension joins a Process-Engine-API deployment and runs last")
  public void theExtensionJoinsAProcessEngineApiDeployment() {

    final var wiring = new PeaCockpitWiring(TestModels.deployed());

    assertEquals(PeaBpmnModel.class, wiring.getModelType());
    assertEquals(PeaProcessingContext.class, wiring.getProcessContextType());
    assertEquals(BusinessCockpitWiringService.ORDER, wiring.getOrder());

  }

  @Test
  @DisplayName("What was wired is what the cockpit later reads a name and a BPMN element from")
  public void whatWasWiredIsRemembered() {

    final var models = new PeaWorkflowModels(TestExtensionHandlers.withoutAnyBpmnName());
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

}
