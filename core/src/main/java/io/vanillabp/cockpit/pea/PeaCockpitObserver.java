package io.vanillabp.cockpit.pea;

import java.time.OffsetDateTime;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks.DeliveredUserTask;
import io.vanillabp.cockpit.pea.PeaWorkflowModels.UserTaskElement;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * What a delivered user task means to the Business Cockpit.
 * <p>
 * It does the same little every BPMS half of the cockpit does: turn what the engine said into
 * the identifiers the cockpit addresses a task by, keep what only the delivery knows, and write
 * one outbox entry. Nothing is sent while the engine's delivery thread waits, and nothing is
 * read back afterwards - on this BPMS there is nothing to read back from.
 * <p>
 * The entry gets a transaction of its own: the Process-Engine-API delivers on a thread of the
 * engine's own with no transaction of the application to join. What that costs is decision 2 in
 * the repository's DECISIONS.md.
 */
public class PeaCockpitObserver implements PeaUserTaskObserver {

  private static final Logger logger = LoggerFactory.getLogger(PeaCockpitObserver.class);

  private final PeaWorkflowModels models;

  private final PeaDeliveredUserTasks deliveredUserTasks;

  private final NameClashAvoidanceSupport scoping;

  private final PeaProcessVersions versions;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  /**
   * @param models What this application deployed
   * @param deliveredUserTasks Where a delivery is remembered for the dispatch which follows it
   * @param scoping VanillaBP's name-clash avoidance, which translates an engine's identifiers
   *          back into the ones the application wrote
   * @param versions What the adapter recorded about the deployed processes
   * @param publisher Where an observed event is handed to, asked for on the first event rather
   *          than up front: this object is built while the application is still wiring itself
   *          together
   */
  public PeaCockpitObserver(
      final PeaWorkflowModels models,
      final PeaDeliveredUserTasks deliveredUserTasks,
      final NameClashAvoidanceSupport scoping,
      final PeaProcessVersions versions,
      final Supplier<BusinessCockpitEventPublisher> publisher) {

    this.models = models;
    this.deliveredUserTasks = deliveredUserTasks;
    this.scoping = scoping;
    this.versions = versions;
    this.publisher = publisher;

  }

  @Override
  public void userTaskDelivered(
      final PeaUserTaskObservation observation) {

    final var bpmnProcessId = plainProcessIdOf(observation);
    final var process = models.of(observation.workflowModuleId(), bpmnProcessId);
    if (process.isEmpty()) {
      logger
          .debug(
              "Process-Engine-API[{}]: not reporting user task '{}': BPMN process '{}' belongs to no workflow module of this application",
              observation.adapterId(),
              observation.taskId(),
              bpmnProcessId);
      return;
    }
    if (observation.workflowAggregateId() == null) {
      logger
          .debug(
              "Process-Engine-API[{}]: not reporting user task '{}' of BPMN process '{}': the delivery named no workflow aggregate to report it for",
              observation.adapterId(),
              observation.taskId(),
              bpmnProcessId);
      return;
    }

    final var element = elementOf(process.get(), observation, bpmnProcessId);
    final var reference = new UserTaskReference(
        observation.adapterId(), observation.workflowModuleId(), bpmnProcessId, observation
            .workflowAggregateId(), workflowIdOf(observation), observation.taskId(), taskDefinitionOf(
                observation, bpmnProcessId, element), element == null
                    ? PeaTaskMeta.text(observation.taskInformation(), PeaTaskMeta.BPMN_TASK_ID)
                    : element.bpmnTaskId());
    deliveredUserTasks
        .remember(
            new DeliveredUserTask(
                reference, detailsOf(observation, process.get(), element, versionOf(
                    observation, bpmnProcessId))));

    reportTheWorkflowOnceItsFirstTaskAppeared(reference);

    publisher
        .get()
        .publishUserTaskEvent(
            reference, kindOf(observation), eventIdOf(observation), OffsetDateTime.now(), EventTransaction.NEW);

  }

  @Override
  public void userTaskTerminated(
      final PeaUserTaskObservation observation) {

    final var known = deliveredUserTasks.ended(observation.taskId());
    if (known.isEmpty()) {
      logger
          .debug(
              "Process-Engine-API[{}]: not reporting the end of user task '{}': this node never saw the task or has forgotten it, and the Process-Engine-API cannot be asked what it was",
              observation.adapterId(),
              observation.taskId());
      return;
    }

    publisher
        .get()
        .publishUserTaskEvent(
            known.get().reference(), endOf(observation), "%s#gone"
                .formatted(observation.taskId()),
            OffsetDateTime.now(), EventTransaction.NEW);

  }

  /**
   * The cockpit lists business cases, and on this BPMS a case becomes visible with the first
   * user task of it: the Process-Engine-API reports neither the start nor the end of a workflow,
   * so there is no other moment to report one. It is reported with the FIRST user task of a case
   * rather than with every one, because a case created again with every task of it is a case
   * whose creation date moves - decision 6 in the repository's DECISIONS.md. A case which the
   * node has since pushed out of its memory is reported as created again.
   */
  private void reportTheWorkflowOnceItsFirstTaskAppeared(
      final UserTaskReference userTask) {

    if (!deliveredUserTasks
        .workflowReportedForTheFirstTime(userTask.adapterId(), userTask.workflowId())) {
      return;
    }
    publisher
        .get()
        .publishWorkflowEvent(
            new WorkflowReference(
                userTask.adapterId(), userTask.workflowModuleId(), userTask.bpmnProcessId(), userTask
                    .workflowAggregateId(), userTask.workflowId()),
            WorkflowEventKind.CREATED, "%s#created".formatted(userTask.workflowId()), OffsetDateTime
                .now(),
            EventTransaction.NEW);

  }

  /**
   * What the cockpit is told about the task, which is what arrived with the delivery plus what
   * the deployed BPMN says the task and the process are called.
   */
  private UserTaskDetailsPrefill detailsOf(
      final PeaUserTaskObservation observation,
      final PeaWorkflowModels.Process process,
      final UserTaskElement element,
      final String bpmnProcessVersion) {

    final var taskInformation = observation.taskInformation();
    return UserTaskDetailsPrefill
        .builder()
        .bpmnProcessVersion(bpmnProcessVersion)
        .bpmnProcessName(process.name())
        // the aggregate's id is the business key of this BPMS: the Process-Engine-API's start
        // command carries no second identifier a case could be looked up by
        .businessId(observation.workflowAggregateId())
        .bpmnTaskName(taskNameOf(taskInformation, element))
        .assignee(PeaTaskMeta.text(taskInformation, PeaTaskMeta.ASSIGNEE))
        .candidateUsers(PeaTaskMeta.list(taskInformation, PeaTaskMeta.CANDIDATE_USERS))
        .candidateGroups(PeaTaskMeta.list(taskInformation, PeaTaskMeta.CANDIDATE_GROUPS))
        .dueDate(PeaTaskMeta.timestamp(taskInformation, PeaTaskMeta.DUE_DATE))
        .followUpDate(PeaTaskMeta.timestamp(taskInformation, PeaTaskMeta.FOLLOW_UP_DATE))
        // the variables the subscription asked the engine for: a '@TaskParam' of a details
        // provider is bound from them, and a variable no subscription asked for is not among
        // them, which the repository's GAPS.md spells out
        .variables(observation.payload())
        .build();

  }

  /**
   * The name of the task: what the engine says it is called, and what the modeller wrote where
   * the engine says nothing.
   */
  private static String taskNameOf(
      final TaskInformation taskInformation,
      final UserTaskElement element) {

    final var reportedByTheEngine = PeaTaskMeta.text(taskInformation, PeaTaskMeta.TASK_NAME);
    if (reportedByTheEngine != null) {
      return reportedByTheEngine;
    }
    return element == null
        ? null
        : element.name();

  }

  /**
   * Whether a task which is gone was finished or withdrawn. The engine says so in the reason
   * where it says anything at all, and a task which simply disappeared is reported as finished -
   * see decision 4 in the repository's DECISIONS.md.
   */
  private static UserTaskEventKind endOf(
      final PeaUserTaskObservation observation) {

    return TaskInformation.DELETE.equals(observation.reason())
        ? UserTaskEventKind.CANCELED
        : UserTaskEventKind.COMPLETED;

  }

  /**
   * The version tag an engine put into the meta map of the delivery, or what this application
   * deployed where it put none.
   */
  private String versionOf(
      final PeaUserTaskObservation observation,
      final String bpmnProcessId) {

    final var tag = PeaTaskMeta
        .text(observation.taskInformation(), PeaTaskMeta.PROCESS_VERSION_TAG);
    if (tag != null) {
      return tag;
    }
    return versions == null
        ? null
        : versions
            .versionOf(observation.adapterId(), observation.workflowModuleId(), bpmnProcessId);

  }

  /**
   * A workflow which the engine does not identify is identified by the aggregate the cockpit
   * shows it for: the Process-Engine-API promises no instance id, and a business case without
   * any id at all would arrive at the cockpit server as a case per event - decision 6 in the
   * repository's DECISIONS.md.
   */
  private static String workflowIdOf(
      final PeaUserTaskObservation observation) {

    final var reported = PeaTaskMeta
        .text(observation.taskInformation(), PeaTaskMeta.WORKFLOW_ID);
    return reported == null
        ? observation.workflowAggregateId()
        : reported;

  }

  /**
   * Which user task of the process was delivered. The engine's meta map names the BPMN element
   * where it is generous, and the subscription's own key answers where it is not.
   */
  private UserTaskElement elementOf(
      final PeaWorkflowModels.Process process,
      final PeaUserTaskObservation observation,
      final String bpmnProcessId) {

    final var bpmnTaskId = PeaTaskMeta
        .text(observation.taskInformation(), PeaTaskMeta.BPMN_TASK_ID);
    if ((bpmnTaskId != null) && process.userTasksByElementId().containsKey(bpmnTaskId)) {
      return process.userTasksByElementId().get(bpmnTaskId);
    }
    return process
        .userTasksByTaskDefinition()
        .get(plainTaskDefinitionOf(observation, bpmnProcessId));

  }

  private String taskDefinitionOf(
      final PeaUserTaskObservation observation,
      final String bpmnProcessId,
      final UserTaskElement element) {

    return element == null
        ? plainTaskDefinitionOf(observation, bpmnProcessId)
        : element.taskDefinition();

  }

  /**
   * The identifiers an engine reports are the ones the workflow module was deployed with, which
   * under <code>use-prefix</code> are not the ones the application wrote. Translating them back
   * is the adapter's rule, so it is asked of VanillaBP's own helper - see decision 1 in the
   * repository's DECISIONS.md.
   */
  private String plainProcessIdOf(
      final PeaUserTaskObservation observation) {

    return scoping == null
        ? observation.bpmnProcessId()
        : scoping
            .plainProcessId(
                observation.workflowModuleId(), observation.bpmnProcessId(), observation
                    .adapterId());

  }

  private String plainTaskDefinitionOf(
      final PeaUserTaskObservation observation,
      final String bpmnProcessId) {

    return scoping == null
        ? observation.taskDefinition()
        : scoping
            .plainTaskDefinition(
                observation.workflowModuleId(), bpmnProcessId, observation.taskDefinition(), observation
                    .adapterId());

  }

  /**
   * Why the engine reported the task decides what the cockpit is told: a task delivered for the
   * first time is new, and a task delivered again changed - its assignee, its candidates or its
   * data. An engine which names no reason is reporting a task the cockpit has not seen either,
   * because a delivery it does not distinguish is a delivery it repeats.
   */
  private static UserTaskEventKind kindOf(
      final PeaUserTaskObservation observation) {

    final var reason = observation.reason();
    return TaskInformation.ASSIGN.equals(reason) || TaskInformation.UPDATE.equals(reason)
        ? UserTaskEventKind.UPDATED
        : UserTaskEventKind.CREATED;

  }

  /**
   * What the cockpit is told this event was called in the BPMS. The engine repeats a delivery
   * under the same task id and the same reason, so the pair identifies the delivery a report
   * came from. It is not what makes two reports collapse - the outbox does that by the adapter,
   * the task and the kind of event, so an assignment and a change waiting at the same time are
   * one update either way.
   */
  private static String eventIdOf(
      final PeaUserTaskObservation observation) {

    return "%s#%s".formatted(
        observation.taskId(), observation.reason() == null
            ? TaskInformation.CREATE
            : observation.reason());

  }

}
