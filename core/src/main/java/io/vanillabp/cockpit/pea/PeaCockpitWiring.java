package io.vanillabp.cockpit.pea;

import io.vanillabp.cockpit.extension.wiring.BusinessCockpitWiringService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;

/**
 * The Process-Engine-API half of the Business Cockpit in VanillaBP's deployment pipeline.
 * <p>
 * It declares the adapter's model and processing-context types, so it takes part in the
 * deployment of a workflow module only where that module runs on the Process-Engine-API. What it
 * does there is remember what was deployed: this BPMS has no repository API, so the models the
 * pipeline read are the only place a process' name, a user task's name and the BPMN element
 * behind a form reference can ever come from.
 * <p>
 * The model is not touched. This BPMS learns about a user task through a subscription rather
 * than through anything written into the BPMN, so there is nothing for the cockpit to add to a
 * file its author wrote.
 * <p>
 * The order is the Business Cockpit's own, the last one, so whatever an adapter or another
 * extension does to a model has happened by the time this runs.
 */
public class PeaCockpitWiring implements ExtensionWiringService<PeaBpmnModel, PeaProcessingContext> {

  private final PeaWorkflowModels models;

  /**
   * @param models Where the deployed models are remembered
   */
  public PeaCockpitWiring(
      final PeaWorkflowModels models) {

    this.models = models;

  }

  @Override
  public Class<PeaBpmnModel> getModelType() {

    return PeaBpmnModel.class;

  }

  @Override
  public Class<PeaProcessingContext> getProcessContextType() {

    return PeaProcessingContext.class;

  }

  @Override
  public int getOrder() {

    return BusinessCockpitWiringService.ORDER;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final PeaBpmnModel model,
      final PeaProcessingContext context) {

    // the model is the file as it was read, with the identifiers the application wrote: what
    // 'use-prefix' rewrites is a copy the adapter deploys, and the plain form is what the
    // cockpit reports
    models.register(workflowModuleId, model);

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) {

    // nothing of this extension has to be started: the user tasks of this BPMS reach it through
    // the adapter's own subscriptions, which call the observer bean this half contributes
  }

}
