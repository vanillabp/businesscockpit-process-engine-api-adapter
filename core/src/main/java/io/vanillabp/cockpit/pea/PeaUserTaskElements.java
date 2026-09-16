package io.vanillabp.cockpit.pea;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.pea.deployment.PeaDeployedProcesses.DeployedProcess;

/**
 * Which user task of a deployed BPMN process something is about.
 * <p>
 * Two sources ask this, and they have to get the same answer. The observer asks it for a task
 * the engine just delivered, and the reader of the platform's delivery log asks it for a task
 * this node never saw. A rule which lived in both places would drift apart, and the cockpit
 * would then show one task under two BPMN elements.
 */
final class PeaUserTaskElements {

  private PeaUserTaskElements() {
  }

  /**
   * The BPMN element of a user task, named where whoever asks knows the element id, and guessed
   * from the form reference where nobody does.
   * <p>
   * A form reference is not unique inside a process, because two user tasks may show the same
   * form. So the first of them wins. That is the rule this extension has always followed, and
   * there is nothing in a delivery or in a delivery record to choose a better one by. What tells
   * two such tasks apart is the BPMN element id, which is why it is asked for first.
   *
   * @param process What the adapter deployed
   * @param bpmnElementId The <code>id</code> attribute of the element, or <code>null</code>
   *          where the source names none
   * @param taskDefinition The plain external form reference, or <code>null</code>
   * @return The element, or <code>null</code> where the deployed process carries no user task
   *         either source names
   */
  static BpmnTaskSpec elementOf(
      final DeployedProcess process,
      final String bpmnElementId,
      final String taskDefinition) {

    if (bpmnElementId != null) {
      final var named = process.userTaskByElementId(bpmnElementId);
      if (named != null) {
        return named;
      }
    }
    if (taskDefinition == null) {
      return null;
    }
    final var showingTheSameForm = process.userTasksByFormReference(taskDefinition);
    return showingTheSameForm.isEmpty()
        ? null
        : showingTheSameForm.getFirst();

  }

}
