package io.vanillabp.cockpit.pea;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger logger = LoggerFactory.getLogger(PeaCockpitWiring.class);

  private final PeaWorkflowModels models;

  private final AtomicBoolean saidWhatIsMissing = new AtomicBoolean();

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

    sayWhatTheCockpitWillNotSeeByItself();

  }

  /**
   * Says once, while the application starts, that the user tasks of this BPMS do not reach the
   * cockpit on their own.
   * <p>
   * A developer who adds this artifact expects to see tasks, and the reason they stay away is
   * neither their configuration nor a defect (decision 5 in the repository's DECISIONS.md): the Process-Engine-API delivers a task to exactly
   * one subscription, so an extension cannot listen next to the workflow application without
   * taking the task away from it, and the VanillaBP Process-Engine-API adapter does not hand its
   * deliveries to an observer yet. Saying so at startup is what keeps that from looking like a
   * broken cockpit. The message goes when the adapter grows the seam.
   */
  private void sayWhatTheCockpitWillNotSeeByItself() {

    if (saidWhatIsMissing.getAndSet(true)) {
      return;
    }
    logger
        .warn(
            """
                The Business Cockpit's Process-Engine-API half is wired: your workflow modules are \
                registered at the cockpit server, and every user task handed to '{}' is reported \
                with the details your application provides. Nothing hands it any yet - the \
                Process-Engine-API delivers a user task to exactly ONE subscription, so this \
                extension must not subscribe next to the VanillaBP Process-Engine-API adapter, and \
                that adapter does not pass its deliveries on. Until it does, call that observer from \
                wherever your application sees user tasks. GAPS.md of \
                businesscockpit-process-engine-api-adapter says what the adapter would have to add.""",
            PeaUserTaskObserver.class.getName());

  }

}
