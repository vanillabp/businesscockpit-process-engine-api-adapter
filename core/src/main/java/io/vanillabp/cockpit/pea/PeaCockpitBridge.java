package io.vanillabp.cockpit.pea;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.pea.PeaAdapter;

/**
 * What the Business Cockpit asks one configured Process-Engine-API adapter, and what this BPMS
 * can answer.
 * <p>
 * Every other BPMS half of the cockpit answers these questions by reading the engine: a task
 * query, a history query, a search for the instances of a business key. The Process-Engine-API
 * has none of them - it is a delivery API, not a query API - so the answers come from what the
 * subscriptions of this node delivered, which is everything this BPMS ever says about a task.
 * The consequences are written down one by one in the repository's <code>GAPS.md</code>, and the
 * wiki says them in the words of somebody using the cockpit.
 */
public class PeaCockpitBridge implements BusinessCockpitBpmsBridge {

  private static final Logger logger = LoggerFactory.getLogger(PeaCockpitBridge.class);

  private final String adapterId;

  private final PeaWorkflowModels models;

  private final PeaDeliveredUserTasks deliveredUserTasks;

  private final PeaProcessVersions versions;

  /**
   * The business cases already reported as unknown. Bounded like everything this half keeps in
   * memory: the sentence is said once per case, until as many other cases have pushed it out, and
   * an application reporting cases this BPMS cannot find must not pay for that with heap.
   */
  private final Set<String> aggregatesReportedAsUnknown;

  /**
   * @param adapterId The configured adapter id this bridge serves
   * @param models What this application deployed
   * @param deliveredUserTasks What this node has seen
   * @param versions What the adapter recorded about the deployed processes
   * @param rememberedAggregates How many business cases this bridge keeps apart while saying
   *          that it knows nothing about them - the number which sizes the memory of the
   *          deliveries themselves, because a case is unknown exactly as long as none of its
   *          tasks is in there
   */
  public PeaCockpitBridge(
      final String adapterId,
      final PeaWorkflowModels models,
      final PeaDeliveredUserTasks deliveredUserTasks,
      final PeaProcessVersions versions,
      final int rememberedAggregates) {

    this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
    this.models = Objects.requireNonNull(models, "models");
    this.deliveredUserTasks = Objects.requireNonNull(deliveredUserTasks, "deliveredUserTasks");
    this.versions = Objects.requireNonNull(versions, "versions");
    this.aggregatesReportedAsUnknown = Collections
        .newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {

          private static final long serialVersionUID = 1L;

          @Override
          protected boolean removeEldestEntry(
              final Map.Entry<String, Boolean> eldest) {

            return size() > rememberedAggregates;

          }

        }));

  }

  @Override
  public String adapterId() {

    return adapterId;

  }

  @Override
  public String adapterType() {

    return PeaAdapter.ADAPTER_TYPE;

  }

  @Override
  public Optional<UserTaskDetailsPrefill> prefilledUserTaskDetails(
      final UserTaskReference userTask) {

    if (!adapterId.equals(userTask.adapterId())) {
      return Optional.empty();
    }
    return deliveredUserTasks
        .of(userTask.userTaskId())
        .filter(this::servedByThisAdapter)
        .map(PeaDeliveredUserTasks.DeliveredUserTask::details);

  }

  @Override
  public Optional<WorkflowDetailsPrefill> prefilledWorkflowDetails(
      final WorkflowReference workflow) {

    if (!adapterId.equals(workflow.adapterId())) {
      return Optional.empty();
    }
    return models
        .of(workflow.workflowModuleId(), workflow.bpmnProcessId())
        .map(
            process -> new WorkflowDetailsPrefill(
                versions
                    .versionOf(adapterId, workflow.workflowModuleId(), workflow.bpmnProcessId()),
                // the business key is the aggregate's id here: the Process-Engine-API's start
                // command carries no business key of its own, so there is no second identifier
                // the cockpit could show
                workflow.workflowAggregateId(), process.name(), null));

  }

  @Override
  public List<WorkflowReference> workflowsOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var workflows = new LinkedHashMap<String, WorkflowReference>();
    deliveredUserTasks
        .ofAggregate(workflowModuleId, bpmnProcessId, workflowAggregateId)
        .stream()
        .filter(this::servedByThisAdapter)
        .forEach(
            task -> workflows
                .putIfAbsent(
                    task.reference().workflowId(),
                    new WorkflowReference(
                        adapterId, workflowModuleId, bpmnProcessId, workflowAggregateId, task
                            .reference()
                            .workflowId())));
    if (workflows.isEmpty()) {
      sayThatNothingIsKnown(workflowModuleId, bpmnProcessId, workflowAggregateId);
    }
    return List.copyOf(workflows.values());

  }

  @Override
  public List<UserTaskReference> userTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final List<String> userTaskIds) {

    final var known = deliveredUserTasks
        .ofAggregate(workflowModuleId, bpmnProcessId, workflowAggregateId)
        .stream()
        .filter(this::servedByThisAdapter)
        .map(PeaDeliveredUserTasks.DeliveredUserTask::reference)
        .toList();
    if (known.isEmpty()) {
      sayThatNothingIsKnown(workflowModuleId, bpmnProcessId, workflowAggregateId);
      return List.of();
    }
    return known
        .stream()
        .filter(
            reference -> (userTaskIds == null) || userTaskIds.isEmpty() || userTaskIds.contains(reference.userTaskId()))
        .toList();

  }

  @Override
  public Optional<UserTaskReference> userTaskOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String userTaskId) {

    return deliveredUserTasks
        .of(userTaskId)
        .filter(this::servedByThisAdapter)
        .filter(task -> !task.ended())
        .map(PeaDeliveredUserTasks.DeliveredUserTask::reference)
        .filter(
            reference -> reference.workflowModuleId().equals(workflowModuleId) && reference.bpmnProcessId()
                .equals(bpmnProcessId) && reference.workflowAggregateId().equals(workflowAggregateId));

  }

  /**
   * Whether a remembered task came from the engine this bridge serves. One node remembers the
   * deliveries of every configured adapter id, and a bridge answers for its own: a task of
   * another engine reported under this adapter id would send the cockpit to the wrong BPMS.
   */
  private boolean servedByThisAdapter(
      final PeaDeliveredUserTasks.DeliveredUserTask task) {

    return task.reference().adapterId().equals(adapterId);

  }

  /**
   * Says that an application reported a change of something this BPMS cannot find again.
   * <p>
   * The Process-Engine-API cannot be asked which workflows or which user tasks belong to a
   * business case: the only ones this half knows are those whose user tasks this node was given,
   * and after the last of them ended it knows none. Both reads answer from that one memory, so
   * they are silent together and say the same sentence. Reporting nothing is the honest answer
   * and an exception would be the wrong one - the application's own work is done and rolling it
   * back over a cockpit update helps nobody. It is said once per business case, because an
   * application which reports every change would otherwise fill the log with the same sentence,
   * and again for a case which as many other ones have pushed out of that memory.
   */
  private void sayThatNothingIsKnown(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var aggregate = "%s|%s|%s"
        .formatted(workflowModuleId, bpmnProcessId, workflowAggregateId);
    if (!aggregatesReportedAsUnknown.add(aggregate)) {
      logger
          .debug(
              "Process-Engine-API[{}]: still nothing known about aggregate '{}'",
              adapterId,
              workflowAggregateId);
      return;
    }
    logger
        .warn(
            """
                Process-Engine-API[{}]: the change of workflow aggregate '{}' (BPMN process '{}' of \
                workflow module '{}') was not reported to the Business Cockpit: this node knows \
                neither a workflow nor a user task of that aggregate. The Process-Engine-API offers \
                no way to search for the workflows or the user tasks of a business case, so both are \
                known while one of its user tasks was delivered to this node and never afterwards - \
                see GAPS.md of businesscockpit-process-engine-api-adapter. Nothing about this case \
                reaches the cockpit until the engine delivers a user task of it to this node again.""",
            adapterId,
            workflowAggregateId,
            bpmnProcessId,
            workflowModuleId);

  }

}
