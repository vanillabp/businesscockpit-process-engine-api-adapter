package io.vanillabp.cockpit.pea.test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.vanillabp.cockpit.pea.PeaWorkflowModels;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.PeaBpmnModel;

/**
 * The workflow module the tests of this module deploy: one BPMN process with one user task, and
 * the configuration an application needs to run it on the Process-Engine-API.
 */
public final class TestModels {

  public static final String ADAPTER_ID = "pea";

  public static final String MODULE_ID = "a-module";

  public static final String BPMN_PROCESS_ID = "ARide";

  public static final String USER_TASK_ELEMENT = "approve";

  public static final String USER_TASK_FORM = "approve-the-ride";

  /**
   * A BPMN file as a modeller would leave it: the names are what the cockpit falls back to when
   * an application writes no title of its own.
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
   * @return The model the adapter's deployment pipeline would hand to the extension
   */
  public static PeaBpmnModel model() {

    return new PeaBpmnModel(
        "a-ride.bpmn", BPMN.getBytes(StandardCharsets.UTF_8), BPMN_PROCESS_ID, List.of(), List
            .of(BpmnTaskSpec.userTask(USER_TASK_ELEMENT, USER_TASK_FORM)));

  }

  /**
   * @return What the extension remembers of a deployed workflow module, with a registry which
   *         knows no BPMN name - the state the Process-Engine-API adapter leaves it in, so the
   *         names come from this extension's own pass over the file
   */
  public static PeaWorkflowModels deployed() {

    return deployed(TestExtensionHandlers.withoutAnyBpmnName());

  }

  /**
   * @param handlers VanillaBP's registry, which answers the name a modeller wrote on an element
   * @return What the extension remembers of a deployed workflow module
   */
  public static PeaWorkflowModels deployed(
      final ExtensionHandlers handlers) {

    final var models = new PeaWorkflowModels(handlers);
    models.register(MODULE_ID, model());
    return models;

  }

  /**
   * @param nameClashAvoidance How the workflow module's identifiers are kept apart
   * @return The configuration of an application running one Process-Engine-API adapter
   */
  public static MigrationAdapterProperties configuration(
      final NameClashAvoidance nameClashAvoidance) {

    final var adapter = new AdapterConfigProperties();
    adapter.setType(PeaAdapter.ADAPTER_TYPE);
    adapter.setNameClashAvoidance(nameClashAvoidance);
    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(ADAPTER_ID, adapter));
    return properties;

  }

}
