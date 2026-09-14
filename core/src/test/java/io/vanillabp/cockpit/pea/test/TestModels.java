package io.vanillabp.cockpit.pea.test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;

/**
 * The workflow module the tests of this module deploy: one BPMN process with one user task, as
 * the Process-Engine-API adapter recorded it while deploying.
 */
public final class TestModels {

  public static final String ADAPTER_ID = "pea";

  public static final String MODULE_ID = "a-module";

  public static final String BPMN_PROCESS_ID = "ARide";

  public static final String PROCESS_NAME = "A taxi ride";

  public static final String USER_TASK_ELEMENT = "approve";

  public static final String USER_TASK_FORM = "approve-the-ride";

  public static final String USER_TASK_NAME = "Approve the ride";

  /** What the engine answered when the adapter deployed the file, the only version there is. */
  public static final String DEPLOYMENT_KEY = "deployment-7";

  /**
   * The BPMN file as a modeller would leave it. The adapter deploys these bytes and reads the two
   * names out of them on the way, so what the model carries below is what the cockpit shows and
   * nothing here opens the file again.
   */
  public static final String BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1">
        <bpmn:process id="ARide" name="A taxi ride" isExecutable="true">
          <bpmn:startEvent id="start" />
          <bpmn:userTask id="approve" name="Approve the ride" />
          <bpmn:endEvent id="end" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  private TestModels() {
  }

  /**
   * @return The model the adapter's deployment pipeline read
   */
  public static PeaBpmnModel model() {

    return model(USER_TASK_NAME);

  }

  /**
   * @param userTaskName The name the modeller wrote on the user task, or <code>null</code> for a
   *          task nobody named
   * @return The model the adapter's deployment pipeline read
   */
  public static PeaBpmnModel model(
      final String userTaskName) {

    return new PeaBpmnModel(
        "a-ride.bpmn", BPMN.getBytes(StandardCharsets.UTF_8), BPMN_PROCESS_ID, PROCESS_NAME, List
            .of(), List.of(BpmnTaskSpec.userTask(USER_TASK_ELEMENT, USER_TASK_FORM, userTaskName)));

  }

  /**
   * @return What the adapter recorded while it deployed the module, under the one adapter id
   *         these tests configure
   */
  public static PeaDeployedProcessesRegistry deployed() {

    return deployed(model());

  }

  /**
   * @param model The model the adapter deployed
   * @return What the adapter recorded while it deployed it
   */
  public static PeaDeployedProcessesRegistry deployed(
      final PeaBpmnModel model) {

    final var deployedProcesses = new PeaDeployedProcessesRegistry();
    deployedProcesses.forAdapter(ADAPTER_ID).record(MODULE_ID, model, DEPLOYMENT_KEY);
    return deployedProcesses;

  }

}
