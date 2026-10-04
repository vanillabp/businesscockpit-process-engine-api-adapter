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
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.election.WorkflowStart;
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
 * <p>
 * A third source names a workflow, and only a workflow. VanillaBP writes down the engine's id of
 * a workflow when it starts it, together with the version where one is known, and
 * {@link WorkflowElection#workflowStartOf} reads that note without asking the engine. It answers
 * for a business case which has no open user task at the moment, such as a workflow which is busy
 * with a service task. Why it comes after the tasks, and what an empty answer means, is decision
 * 14.
 */
public class PeaCockpitBridge implements BusinessCockpitBpmsBridge {

  private static final Logger logger = LoggerFactory.getLogger(PeaCockpitBridge.class);

  private final String adapterId;

  private final PeaDeployedProcessesRegistry deployedProcesses;

  private final PeaDeliveredUserTasks deliveredUserTasks;

  private final PeaRecordedUserTasks recordedUserTasks;

  private final PeaProcessVersions versions;

  private final WorkflowElection election;

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
   * @param election VanillaBP's election, asked only for the id it wrote down when a workflow
   *          started
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
      final WorkflowElection election,
      final int rememberedAggregates) {

    this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
    this.deployedProcesses = Objects.requireNonNull(deployedProcesses, "deployedProcesses");
    this.deliveredUserTasks = Objects.requireNonNull(deliveredUserTasks, "deliveredUserTasks");
    this.recordedUserTasks = Objects.requireNonNull(recordedUserTasks, "recordedUserTasks");
    this.versions = Objects.requireNonNull(versions, "versions");
    this.election = Objects.requireNonNull(election, "election");
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
                shownVersionOf(workflow),
                // the business key is the aggregate's id here. The Process-Engine-API's start
                // command carries no business key of its own, so there is no second identifier
                // the cockpit could show
                workflow.workflowAggregateId(), process.processName(), null));

  }

  /**
   * The version the cockpit SHOWS for a business case, which is the version it shows for the user
   * tasks of that case. Only a delivery carries the version tag of this BPMS, so the last task of
   * the workflow this node was given answers. Where this node holds none, the version the
   * reference names answers, which is the one VanillaBP wrote down for the workflow and the one
   * that picked the details provider.
   * <p>
   * Falling back to what this application deployed is the normal case and not an exception, which
   * is worth saying because it reads like dead code. A node remembers a bounded number of
   * deliveries (<code>vanillabp.cockpit.process-engine-api.remembered-user-tasks</code>), so every
   * business case whose tasks were pushed out of that memory is answered with the deployment key,
   * even where its tasks once carried a tag. So is every case this node never saw a task of.
   * Entry 5 in the repository's GAPS.md says what that costs.
   */
  private String shownVersionOf(
      final WorkflowReference workflow) {

    final var namedWithATask = deliveredUserTasks
        .versionOfWorkflow(
            adapterId, workflow.workflowModuleId(), workflow.bpmnProcessId(), workflow
                .workflowId());
    if (namedWithATask != null) {
      return namedWithATask;
    }
    return hasAVersion(workflow.processVersion())
        ? workflow.processVersion()
        : versions.versionOf(adapterId, workflow.workflowModuleId(), workflow.bpmnProcessId());

  }

  /**
   * The workflows of one business case, each under the version of the user task it was found
   * through. A task this node was delivered carries the version the engine named with it, and a
   * task only the delivery log knows carries none, because the log holds no version. Both are
   * the honest answer: there is no catalogue to ask what a running workflow was started on.
   * <p>
   * A case with no open task is answered by what VanillaBP wrote down when it started the
   * workflow. The version is the one VanillaBP wrote down with it, which it took from a delivery of
   * that workflow where the start named none. Where VanillaBP has none, a delivery of that workflow
   * this node still remembers answers. Where there is none either, the workflow is left out with a
   * warning, because a report without a version would empty the details the cockpit shows. The
   * tasks come first, because they name the id the cockpit already shows the case under (decision
   * 14).
   * <p>
   * The same holds whether VanillaBP says that a version may still come or that it never will
   * ({@link WorkflowStart#versionsAreReported}). "Never" is also its answer for a note which names
   * no adapter and for an adapter which said nothing about versions, so it does not prove that no
   * details provider of the application names a version.
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
    if (!workflows.isEmpty()) {
      return List.copyOf(workflows.values());
    }
    final var started = startedByVanillaBp(workflowModuleId, bpmnProcessId, workflowAggregateId);
    if (started.isEmpty()) {
      sayThatNothingIsKnown(workflowModuleId, bpmnProcessId, workflowAggregateId);
      return List.of();
    }
    final var workflowId = started.get().workflowId();
    final var version = hasAVersion(started.get().processVersion())
        ? started.get().processVersion()
        : deliveredUserTasks.versionOfWorkflow(adapterId, workflowModuleId, bpmnProcessId, workflowId);
    if (version == null) {
      // the version picks the @WorkflowDetailsProvider method. Without one, a method which names
      // a version is passed over, and the report would replace the details the cockpit shows
      // with an empty map. No report leaves them as they are
      sayThatTheVersionIsUnknown(workflowModuleId, bpmnProcessId, workflowAggregateId, workflowId);
      return List.of();
    }
    return List
        .of(
            new WorkflowReference(
                adapterId, workflowModuleId, bpmnProcessId, version, workflowAggregateId, workflowId));

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
      if (startedByVanillaBp(workflowModuleId, bpmnProcessId, workflowAggregateId).isPresent()) {
        // a running workflow without an open user task is nothing to warn about. It is what a
        // workflow looks like between two user tasks
        logger
            .debug(
                "Process-Engine-API[{}]: no open user task of aggregate '{}' is known",
                adapterId,
                workflowAggregateId);
      } else {
        sayThatNothingIsKnown(workflowModuleId, bpmnProcessId, workflowAggregateId);
      }
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
   * What VanillaBP wrote down when it started the workflow of one business case on this engine:
   * the engine's id of the workflow and, where it knows it, the version.
   * <p>
   * The answer says what was true at the start. It does not say that the workflow still runs, so
   * it only names the workflow in a report and is never sent to the engine. That is also why it
   * serves a change reported after the workflow ended: the cockpit hears of it, the engine does
   * not.
   * <p>
   * A note of another adapter is left out, because its id belongs to another engine. A note which
   * names no adapter is taken, because that is an id VanillaBP knows from its election cache.
   *
   * @return The note, or empty where VanillaBP does not know the workflow. That covers a workflow
   *         nobody started, one started before VanillaBP wrote such notes, one whose note is too
   *         old to be kept, and an engine which answered the start with no id. Nobody can tell
   *         these apart, so nothing is read into an empty answer
   */
  private Optional<WorkflowStart> startedByVanillaBp(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return election
        .workflowStartOf(workflowModuleId, bpmnProcessId, workflowAggregateId)
        .filter(start -> (start.adapterId() == null) || start.adapterId().equals(adapterId));

  }

  /**
   * Whether a version was named at all. An empty text is no version, the same as a missing one.
   */
  private static boolean hasAVersion(
      final String version) {

    return (version != null) && !version.isBlank();

  }

  /**
   * Puts a task of the delivery log under the workflow a delivery of the same business case
   * named.
   * <p>
   * A record names the workflow where the engine named it in the delivery. A record of an engine
   * which named none, or one written before the adapter filled the field, names no workflow, and
   * the reader falls back to the aggregate's id. Where a delivery of the same case is in the
   * memory, the engine's own id for that workflow may still be known, and the two answers must
   * not stand next to each other: the cockpit would show one
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
   * Says that VanillaBP started the workflow of a changed business case, but that its change is
   * not reported, because nothing on this node says which version the workflow runs on.
   * <p>
   * Only a delivery carries a version on this BPMS, and the version picks the
   * <code>@WorkflowDetailsProvider</code> method. A report without one passes over every method
   * which names a version, and the cockpit would then replace the details it shows with an empty
   * map. Like the sentence of {@link #sayThatNothingIsKnown}, it is said once per business case.
   */
  private void sayThatTheVersionIsUnknown(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String workflowId) {

    final var aggregate = "version|%s|%s|%s"
        .formatted(workflowModuleId, bpmnProcessId, workflowAggregateId);
    if (!aggregatesReportedAsUnknown.add(aggregate)) {
      logger
          .debug(
              "Process-Engine-API[{}]: still no version known of workflow '{}' of aggregate '{}'",
              adapterId,
              workflowId,
              workflowAggregateId);
      return;
    }
    logger
        .warn(
            """
                Process-Engine-API[{}]: the change of workflow aggregate '{}' (BPMN process '{}' of \
                workflow module '{}') was not reported to the Business Cockpit, because the version \
                of its workflow '{}' is unknown. VanillaBP started that workflow, and the case has no \
                open user task right now. On the Process-Engine-API only a delivered user task names \
                the version. VanillaBP wrote down none for this workflow, and this node holds no \
                delivery of it either, for example after a restart or because another node got it, \
                or because the engine fills no version tag. The version picks the \
                @WorkflowDetailsProvider method, so a report without one would replace the details \
                the cockpit shows with nothing. The cockpit keeps what it shows until the engine \
                delivers the next user task of this workflow to this node.""",
            adapterId,
            workflowAggregateId,
            bpmnProcessId,
            workflowModuleId,
            workflowId);

  }

  /**
   * Says that an application reported a change of something this BPMS cannot find again.
   * <p>
   * The Process-Engine-API cannot be asked which workflows or which user tasks belong to a
   * business case. Three sources answer instead, and this is said when NONE of them knows
   * anything: this node saw no open task of the case, VanillaBP wrote none down either, and
   * VanillaBP wrote down no start of its workflow. Both reads ask the same sources, so they are
   * silent together and say the same sentence.
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
                workflow module '{}') was not reported to the Business Cockpit, because no source \
                knows a workflow or a user task of that aggregate. The Process-Engine-API offers no \
                way to search for the workflows or the user tasks of a business case, so this half \
                answers from the deliveries this node was given, from the deliveries VanillaBP wrote \
                into its own delivery log, and from the id VanillaBP wrote down when it started the \
                workflow. The memory holds a task until the engine takes it away, the log holds one \
                until the application completes it, a user task no @WorkflowTask method of this \
                application claims was never written down, and the note of a start is kept for \
                vanillabp.delivery.workflow-start-retention; see \
                GAPS.md of businesscockpit-process-engine-api-adapter. Nothing about this case \
                reaches the cockpit until the engine delivers a user task of it to this node \
                again.""",
            adapterId,
            workflowAggregateId,
            bpmnProcessId,
            workflowModuleId);

  }

}
