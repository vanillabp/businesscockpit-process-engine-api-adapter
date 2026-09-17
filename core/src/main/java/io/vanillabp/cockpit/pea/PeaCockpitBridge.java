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
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;

/**
 * What the Business Cockpit asks one configured Process-Engine-API adapter, and what this BPMS
 * can answer.
 * <p>
 * Every other BPMS half of the cockpit answers these questions by reading the engine: a task
 * query, a history query, a search for the instances of a business key. The Process-Engine-API has
 * none of them, because it is a delivery API and not a query API. So the answers come from what
 * the subscriptions of this node delivered, which is everything this BPMS ever says about a task.
 * What that costs is written down one by one in the repository's <code>GAPS.md</code>, and the
 * wiki says it in the words of somebody using the cockpit.
 * <p>
 * There is a second source, and it answers the other half of the question. VanillaBP writes down
 * every delivery it processed, in the application's own database, so that log says what this
 * application reported and how it ended after a restart and on any node. The memory answers
 * first, because it carries more, and the log adds the tasks the memory never saw or has
 * forgotten. The border between the two is decision 9 in the repository's DECISIONS.md.
 */
public class PeaCockpitBridge implements BusinessCockpitBpmsBridge {

  private static final Logger logger = LoggerFactory.getLogger(PeaCockpitBridge.class);

  private final String adapterId;

  private final PeaDeployedProcessesRegistry deployedProcesses;

  private final PeaDeliveredUserTasks deliveredUserTasks;

  private final PeaRecordedUserTasks recordedUserTasks;

  private final PeaProcessVersions versions;

  /**
   * The business cases already reported as unknown. It is bounded like everything this half keeps
   * in memory. The sentence is said once per case, until enough other cases have pushed that case
   * out, and an application which reports cases this BPMS cannot find must not pay for it with
   * heap.
   */
  private final Set<String> aggregatesReportedAsUnknown;

  /**
   * @param adapterId The configured adapter id this bridge serves
   * @param deployedProcesses What the adapter deployed, one record per configured adapter id
   * @param deliveredUserTasks What this node has seen
   * @param recordedUserTasks What VanillaBP wrote down about the deliveries it processed
   * @param versions What the adapter recorded about the deployed processes
   * @param rememberedAggregates How many business cases this bridge keeps apart while saying that
   *          it knows nothing about them. It is the number which sizes the memory of the
   *          deliveries themselves, because a case is unknown exactly as long as none of its tasks
   *          is in there
   */
  public PeaCockpitBridge(
      final String adapterId,
      final PeaDeployedProcessesRegistry deployedProcesses,
      final PeaDeliveredUserTasks deliveredUserTasks,
      final PeaRecordedUserTasks recordedUserTasks,
      final PeaProcessVersions versions,
      final int rememberedAggregates) {

    this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
    this.deployedProcesses = Objects.requireNonNull(deployedProcesses, "deployedProcesses");
    this.deliveredUserTasks = Objects.requireNonNull(deliveredUserTasks, "deliveredUserTasks");
    this.recordedUserTasks = Objects.requireNonNull(recordedUserTasks, "recordedUserTasks");
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

  /**
   * What the cockpit shows about a task, which only the memory holds. A delivery record carries
   * identifiers and an outcome and not one word the engine said about the task, so there is
   * nothing to read there.
   * <p>
   * The report of a delivered task is built right after that delivery was remembered, so it is
   * answered. The other two callers may find nothing: a report an application asks for with
   * <code>aggregateChanged</code>, where the task is one only the delivery log knows, and the read
   * behind <code>getUserTask</code>. A report which finds nothing here is dropped, and what that
   * costs is entry 10 in the repository's GAPS.md.
   */
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
    return Optional
        .ofNullable(
            deployedProcesses
                .forAdapter(adapterId)
                .deployedVersionOf(workflow.workflowModuleId(), workflow.bpmnProcessId()))
        .map(
            process -> new WorkflowDetailsPrefill(
                versions
                    .versionOf(adapterId, workflow.workflowModuleId(), workflow.bpmnProcessId()),
                // the business key is the aggregate's id here. The Process-Engine-API's start
                // command carries no business key of its own, so there is no second identifier
                // the cockpit could show
                workflow.workflowAggregateId(), process.processName(), null));

  }

  /**
   * The workflows of one business case, each under the version of the user task it was found
   * through. A task this node was delivered carries the version the engine named with it, and a
   * task only the delivery log knows carries none, because the log holds no version. Both are
   * the honest answer: there is no catalogue to ask what a running workflow was started on.
   */
  @Override
  public List<WorkflowReference> workflowsOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var workflows = new LinkedHashMap<String, WorkflowReference>();
    openTasksOfAggregate(workflowModuleId, bpmnProcessId, workflowAggregateId)
        .forEach(
            userTask -> workflows
                .putIfAbsent(
                    userTask.workflowId(),
                    new WorkflowReference(
                        adapterId, workflowModuleId, bpmnProcessId, userTask
                            .processVersion(), workflowAggregateId, userTask
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

    final var known = openTasksOfAggregate(
        workflowModuleId, bpmnProcessId, workflowAggregateId);
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

    final var remembered = deliveredUserTasks
        .of(userTaskId)
        .filter(this::servedByThisAdapter);
    if (remembered.isPresent()) {
      return remembered
          .filter(task -> !task.ended())
          .map(PeaDeliveredUserTasks.DeliveredUserTask::reference)
          .filter(
              reference -> reference.workflowModuleId().equals(workflowModuleId) && reference.bpmnProcessId()
                  .equals(bpmnProcessId) && reference.workflowAggregateId().equals(workflowAggregateId));
    }
    return recordedUserTasks
        .openTaskOfAggregate(
            adapterId, workflowModuleId, bpmnProcessId, workflowAggregateId, userTaskId);

  }

  /**
   * The open user tasks of one business case, out of both sources.
   * <p>
   * The memory answers first and the log fills the gaps, which is the rule decision 9 in the
   * repository's DECISIONS.md writes down. It is applied per task rather than per business case:
   * a task the memory holds is the memory's answer, even where the memory says the task is over
   * and the log still has it open. The memory saw the engine take that task away, and the log
   * only learns of an end which the application asked for.
   */
  private List<UserTaskReference> openTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var open = new LinkedHashMap<String, UserTaskReference>();
    deliveredUserTasks
        .ofAggregate(workflowModuleId, bpmnProcessId, workflowAggregateId)
        .stream()
        .filter(this::servedByThisAdapter)
        .map(PeaDeliveredUserTasks.DeliveredUserTask::reference)
        .forEach(reference -> open.put(reference.userTaskId(), reference));
    final var workflowNamedByTheEngine = open
        .values()
        .stream()
        .findFirst()
        .map(UserTaskReference::workflowId);
    recordedUserTasks
        .openTasksOfAggregate(adapterId, workflowModuleId, bpmnProcessId, workflowAggregateId)
        .stream()
        .filter(recorded -> !remembers(recorded.userTaskId()))
        .map(recorded -> under(recorded, workflowNamedByTheEngine))
        .forEach(recorded -> open.putIfAbsent(recorded.userTaskId(), recorded));
    return List.copyOf(open.values());

  }

  /**
   * Puts a task of the delivery log under the workflow a delivery of the same business case
   * named.
   * <p>
   * A record written on this BPMS names no workflow, so the reader falls back to the aggregate's
   * id. Where a delivery of the same case is in the memory, the engine's own id for that workflow
   * is known, and the two answers must not stand next to each other: the cockpit would show one
   * business case twice, once under each id. The memory's answer wins here for the same reason it
   * wins everywhere else, and a case with no delivery in the memory keeps the aggregate's id, as
   * decision 6 in the repository's DECISIONS.md says it should.
   */
  private static UserTaskReference under(
      final UserTaskReference recorded,
      final Optional<String> workflowNamedByTheEngine) {

    return workflowNamedByTheEngine
        .filter(workflowId -> !workflowId.equals(recorded.workflowId()))
        .map(
            workflowId -> new UserTaskReference(
                recorded.adapterId(), recorded.workflowModuleId(), recorded.bpmnProcessId(), recorded
                    .processVersion(), recorded.workflowAggregateId(), workflowId, recorded.userTaskId(), recorded
                        .taskDefinition(), recorded.bpmnTaskId()))
        .orElse(recorded);

  }

  /**
   * Whether this node saw the delivery of a task itself, whether or not the task is over. It is
   * what keeps the log from answering about a task the memory has a better answer for.
   */
  private boolean remembers(
      final String userTaskId) {

    return deliveredUserTasks
        .of(userTaskId)
        .filter(this::servedByThisAdapter)
        .isPresent();

  }

  /**
   * Whether a remembered task came from the engine this bridge serves. One node remembers the
   * deliveries of every configured adapter id, and a bridge answers for its own. A task of another
   * engine reported under this adapter id would send the cockpit to the wrong BPMS.
   */
  private boolean servedByThisAdapter(
      final PeaDeliveredUserTasks.DeliveredUserTask task) {

    return task.reference().adapterId().equals(adapterId);

  }

  /**
   * Says that an application reported a change of something this BPMS cannot find again.
   * <p>
   * The Process-Engine-API cannot be asked which workflows or which user tasks belong to a
   * business case. Two sources answer instead, and this is said when NEITHER of them knows
   * anything: this node saw no open task of the case, and VanillaBP wrote none down either.
   * Both reads use the same two sources, so they are silent together and say the same sentence.
   * Reporting nothing is the honest answer, and an exception would be the wrong one, because the
   * application's own work is done and rolling it back over a cockpit update helps nobody. The
   * sentence is said once per business case, because an application which reports every change
   * would otherwise fill the log with it. It is said again for a case which enough other ones
   * have pushed out of the memory.
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
                workflow module '{}') was not reported to the Business Cockpit, because neither \
                source knows a workflow or a user task of that aggregate. The Process-Engine-API \
                offers no way to search for the workflows or the user tasks of a business case, so \
                this half answers from the deliveries this node was given and from the deliveries \
                VanillaBP wrote into its own delivery log. The memory holds a task until the engine \
                takes it away, the log holds one until the application completes it, and a user \
                task no @WorkflowTask method of this application claims was never written down; see \
                GAPS.md of businesscockpit-process-engine-api-adapter. Nothing about this case \
                reaches the cockpit until the engine delivers a user task of it to this node \
                again.""",
            adapterId,
            workflowAggregateId,
            bpmnProcessId,
            workflowModuleId);

  }

}
