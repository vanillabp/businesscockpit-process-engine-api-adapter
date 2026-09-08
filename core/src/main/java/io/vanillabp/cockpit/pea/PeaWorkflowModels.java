package io.vanillabp.cockpit.pea;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.pea.PeaBpmnModel;

/**
 * What this application deployed to the Process-Engine-API, as far as the cockpit needs it.
 * <p>
 * An engine which delivers a user task says which task it is and, where it fills the meta map
 * generously, which element of which process instance. It says nothing about how the task and
 * the process are called, and it cannot be asked afterwards: the Process-Engine-API has no
 * repository API. So the models VanillaBP's deployment pipeline read are kept here while the
 * pipeline runs, and everything the cockpit shows about a task beyond the engine's own words
 * comes from them.
 * <p>
 * A process which is not in here belongs to no workflow module of this application, and a task
 * of it is passed over rather than reported under a module it does not belong to.
 */
public class PeaWorkflowModels {

  /**
   * One user task of one BPMN process, as the modeller wrote it.
   *
   * @param bpmnTaskId The BPMN element id, one of the two keys a details provider is matched by
   * @param taskDefinition The external form reference, the other key, and what the cockpit shows
   *          a form for
   * @param name The BPMN name of the task, the cockpit's fallback title
   */
  public record UserTaskElement(
                                String bpmnTaskId,
                                String taskDefinition,
                                String name) {
  }

  /**
   * One BPMN process of one workflow module.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id, as the application wrote it
   * @param name The BPMN name of the process, the cockpit's fallback title for a business case
   * @param userTasksByElementId The user tasks, by BPMN element id
   * @param userTasksByTaskDefinition The same user tasks, by their plain external form reference
   */
  public record Process(
                        String workflowModuleId,
                        String bpmnProcessId,
                        String name,
                        Map<String, UserTaskElement> userTasksByElementId,
                        Map<String, UserTaskElement> userTasksByTaskDefinition) {
  }

  private final Map<String, Process> byModuleAndProcess = new ConcurrentHashMap<>();

  /**
   * Remembers one BPMN process VanillaBP is deploying to the Process-Engine-API. Called while
   * the deployment pipeline runs and therefore before any subscription of that module opens.
   *
   * @param workflowModuleId The workflow module
   * @param model The model the adapter read
   */
  public void register(
      final String workflowModuleId,
      final PeaBpmnModel model) {

    byModuleAndProcess
        .computeIfAbsent(
            key(workflowModuleId, model.bpmnProcessId()),
            key -> processOf(workflowModuleId, model));

  }

  /**
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   * @return What was deployed, or empty where this application deployed no such process
   */
  public Optional<Process> of(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return Optional
        .ofNullable(byModuleAndProcess.get(key(workflowModuleId, bpmnProcessId)));

  }

  private static Process processOf(
      final String workflowModuleId,
      final PeaBpmnModel model) {

    final var names = PeaBpmnNames.read(model.resource(), model.bpmnProcessId());
    final var byElementId = new LinkedHashMap<String, UserTaskElement>();
    final var byTaskDefinition = new LinkedHashMap<String, UserTaskElement>();
    model
        .userTasks()
        .forEach(userTask -> {
          final var element = new UserTaskElement(
              userTask.activityId(), userTask.taskDefinition(), names
                  .userTaskNames()
                  .get(userTask.activityId()));
          byElementId.put(element.bpmnTaskId(), element);
          if (element.taskDefinition() != null) {
            byTaskDefinition.putIfAbsent(element.taskDefinition(), element);
          }
        });
    return new Process(
        workflowModuleId, model.bpmnProcessId(), names.processName(), Map.copyOf(byElementId), Map
            .copyOf(byTaskDefinition));

  }

  /**
   * Joins the two halves of a process' identity. A BPMN process id carries no blank, so the two
   * halves can never be read as another pair.
   */
  private static String key(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return workflowModuleId
        + " "
        + bpmnProcessId;

  }

}
