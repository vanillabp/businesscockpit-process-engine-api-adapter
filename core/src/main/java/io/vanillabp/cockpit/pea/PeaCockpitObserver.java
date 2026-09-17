package io.vanillabp.cockpit.pea;

import java.time.OffsetDateTime;
import java.util.Objects;
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
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.pea.deployment.PeaDeployedProcesses.DeployedProcess;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import io.vanillabp.pea.wiring.PeaTaskMeta;

/**
 * What a delivered user task means to the Business Cockpit.
 * <p>
 * It does the same little every BPMS half of the cockpit does. It turns what the engine said into
 * the identifiers the cockpit addresses a task by, keeps what only the delivery knows, and hands
 * the event over. The cockpit builds the report there and then and writes it into one outbox
 * entry, so the report says what the task looked like when the engine handed it over. Nothing is
 * sent to the cockpit server while the engine's delivery thread waits, and nothing is read back
 * afterwards, because on this BPMS there is nothing to read back from.
 * <p>
 * The entry gets a transaction of its own. The Process-Engine-API delivers on a thread of the
 * engine's own, and there is no transaction of the application to join. What that costs is
 * decision 2 in the repository's DECISIONS.md.
 * <p>
 * Building the report runs the application's details provider on this thread. An exception out of
 * it writes no entry and leaves this class: the adapter turns it into a failed delivery, so the
 * engine hears about the broken provider and offers the task again. The application keeps its
 * notification, because the adapter calls its {@code @WorkflowTask} method before it lets the
 * failure out. The end of a task is the quiet half: a termination is not offered a second time, so
 * a report which fails there is gone. That is decision 11 in the repository's DECISIONS.md.
 * <p>
 * The adapter hands over PLAIN identifiers, because it translates what an engine reports back
 * through name-clash avoidance before it builds an observation. So nothing here spells an id a
 * second time. Two identifiers may be missing, and both times it is the adapter saying so rather
 * than guessing. A delivery whose BPMN process cannot be told is passed over with a line naming
 * the task. A termination names no process at all, which costs nothing, because what a terminated
 * task was is remembered from its delivery.
 * <p>
 * What a delivery says beyond those identifiers is read under the key names the adapter itself
 * writes ({@link PeaTaskMeta}). The Process-Engine-API defines no vocabulary for the meta map of
 * a delivered task. One spelling on both sides is what keeps the cockpit from showing a detail as
 * missing which the engine did fill.
 */
public class PeaCockpitObserver implements PeaUserTaskObserver {

  private static final Logger logger = LoggerFactory.getLogger(PeaCockpitObserver.class);

  private final PeaDeployedProcessesRegistry deployedProcesses;

  private final PeaDeliveredUserTasks deliveredUserTasks;

  private final PeaProcessVersions versions;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  /**
   * @param deployedProcesses What the adapter deployed, one record per configured adapter id
   * @param deliveredUserTasks Where a delivery is remembered, for the report built from it and
   *          for the reads which come later
   * @param versions What the adapter recorded about the deployed processes
   * @param publisher Where an observed event is handed to. It is asked for on the first event
   *          rather than up front, because this object is built while the application is still
   *          wiring itself together
   */
  public PeaCockpitObserver(
      final PeaDeployedProcessesRegistry deployedProcesses,
      final PeaDeliveredUserTasks deliveredUserTasks,
      final PeaProcessVersions versions,
      final Supplier<BusinessCockpitEventPublisher> publisher) {

    this.deployedProcesses = Objects.requireNonNull(deployedProcesses, "deployedProcesses");
    this.deliveredUserTasks = Objects.requireNonNull(deliveredUserTasks, "deliveredUserTasks");
    this.versions = Objects.requireNonNull(versions, "versions");
    this.publisher = Objects.requireNonNull(publisher, "publisher");

  }

  @Override
  public void userTaskDelivered(
      final PeaUserTaskObservation observation) {

    final var bpmnProcessId = observation.bpmnProcessId();
    if (bpmnProcessId == null) {
      // the adapter could not tell which of the processes behind this subscription the task
      // belongs to, so there is no workflow to report the task under
      logger
          .debug(
              "Process-Engine-API[{}]: not reporting user task '{}': the delivery named no BPMN process",
              observation.adapterId(),
              observation.taskId());
      return;
    }
    // the deployment of the engine which delivered the task is the one to ask. Two configured
    // adapter ids may run the same workflow module on engines of their own, and a task of one of
    // them says nothing about what the other deployed
    final var process = deployedProcesses
        .forAdapter(observation.adapterId())
        .deployedVersionOf(observation.workflowModuleId(), bpmnProcessId);
    if (process == null) {
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

    final var element = elementOf(process, observation);
    final var reference = new UserTaskReference(
        observation.adapterId(), observation.workflowModuleId(), bpmnProcessId, reportedVersionOf(
            observation), observation
                .workflowAggregateId(), workflowIdOf(observation), observation.taskId(), taskDefinitionOf(
                    observation, element), element == null
                        ? PeaTaskMeta.text(observation.taskInformation(), PeaTaskMeta.BPMN_TASK_ID)
                        : element.activityId());
    // remembered BEFORE the event is handed over, and the order matters. The cockpit builds the
    // report inside the call below and asks the bridge what the delivery said, and the bridge has
    // nowhere else to read that from
    deliveredUserTasks
        .remember(
            new DeliveredUserTask(
                reference, detailsOf(observation, process, element, shownVersionOf(
                    observation, bpmnProcessId))));

    reportTheWorkflowOnceItsFirstTaskAppeared(reference);

    publisher
        .get()
        .publishUserTaskEvent(
            reference, kindOf(observation), eventIdOf(observation), OffsetDateTime.now(), EventTransaction.NEW);

  }

  /**
   * The engine took a user task away, and the cockpit is told how it ended.
   * <p>
   * This is the one read which cannot fall back on VanillaBP's delivery log. A termination
   * carries no workflow aggregate, because the engine hands over no payload with it, and the log
   * can only be asked with the workflow module, the BPMN process, the aggregate and the task. So
   * a node which never saw the delivery has nothing to report the end of. That gap is entry 11 in
   * the repository's GAPS.md.
   */
  @Override
  public void userTaskTerminated(
      final PeaUserTaskObservation observation) {

    final var known = deliveredUserTasks.of(observation.taskId());
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
    // only now. The report of the end is built inside the call above and reads what the delivery
    // said from here. A report which did not get written leaves the task open here, which is all
    // this half can do: the failure reaches the engine, and an engine behind this API repeats a
    // delivery but not a termination (decision 11 in the repository's DECISIONS.md)
    deliveredUserTasks.ended(observation.taskId());

  }

  /**
   * The cockpit lists business cases, and on this BPMS a case becomes visible with its first user
   * task. The Process-Engine-API reports neither the start nor the end of a workflow, so there is
   * no other moment to report one. It is reported with the FIRST user task of a case rather than
   * with every one, because a case created again with every task of it is a case whose creation
   * date moves. That is decision 6 in the repository's DECISIONS.md. A case which the node has
   * since pushed out of its memory is reported as created again.
   */
  private void reportTheWorkflowOnceItsFirstTaskAppeared(
      final UserTaskReference userTask) {

    if (deliveredUserTasks.workflowWasReported(userTask.adapterId(), userTask.workflowId())) {
      return;
    }
    publisher
        .get()
        .publishWorkflowEvent(
            new WorkflowReference(
                userTask.adapterId(), userTask.workflowModuleId(), userTask.bpmnProcessId(), userTask
                    .processVersion(), userTask.workflowAggregateId(), userTask.workflowId()),
            WorkflowEventKind.CREATED, "%s#created".formatted(userTask.workflowId()), OffsetDateTime
                .now(),
            EventTransaction.NEW);
    // only now. A report which did not get written leaves the case unreported, so the next user
    // task of the case makes it appear, and so does the repeated delivery of this one
    deliveredUserTasks.rememberWorkflowWasReported(userTask.adapterId(), userTask.workflowId());

  }

  /**
   * What the cockpit is told about the task, which is what arrived with the delivery plus what
   * the deployed BPMN says the task and the process are called.
   */
  private UserTaskDetailsPrefill detailsOf(
      final PeaUserTaskObservation observation,
      final DeployedProcess process,
      final BpmnTaskSpec element,
      final String bpmnProcessVersion) {

    final var taskInformation = observation.taskInformation();
    return UserTaskDetailsPrefill
        .builder()
        .bpmnProcessVersion(bpmnProcessVersion)
        .bpmnProcessName(process.processName())
        // the aggregate's id is the business key of this BPMS. The Process-Engine-API's start
        // command carries no second identifier a case could be looked up by
        .businessId(observation.workflowAggregateId())
        .bpmnTaskName(taskNameOf(taskInformation, element))
        .assignee(PeaTaskMeta.text(taskInformation, PeaTaskMeta.ASSIGNEE))
        .candidateUsers(PeaTaskMeta.list(taskInformation, PeaTaskMeta.CANDIDATE_USERS))
        .candidateGroups(PeaTaskMeta.list(taskInformation, PeaTaskMeta.CANDIDATE_GROUPS))
        .dueDate(PeaTaskMeta.timestamp(taskInformation, PeaTaskMeta.DUE_DATE))
        .followUpDate(PeaTaskMeta.timestamp(taskInformation, PeaTaskMeta.FOLLOW_UP_DATE))
        // the variables the subscription asked the engine for. A '@TaskParam' of a details
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
      final BpmnTaskSpec element) {

    final var reportedByTheEngine = PeaTaskMeta.text(taskInformation, PeaTaskMeta.TASK_NAME);
    if (reportedByTheEngine != null) {
      return reportedByTheEngine;
    }
    return element == null
        ? null
        : element.name();

  }

  /**
   * Whether a task which is gone was finished or withdrawn. The engine says so in the reason,
   * where it says anything at all, and a task which simply disappeared is reported as finished.
   * See decision 4 in the repository's DECISIONS.md.
   */
  private static UserTaskEventKind endOf(
      final PeaUserTaskObservation observation) {

    return TaskInformation.DELETE.equals(observation.reason())
        ? UserTaskEventKind.CANCELED
        : UserTaskEventKind.COMPLETED;

  }

  /**
   * The version of the BPMN process a delivered task belongs to, as far as this BPMS reports
   * one. It is the version tag the engine wrote into the meta map of THAT task, and nothing
   * where the engine wrote none.
   * <p>
   * This is the version a details provider is picked by, so only a value which says which model
   * the task came from belongs here. The deployment key this application got when it deployed
   * its files does not: it names one deployment of one node and changes every time the files go
   * out again, so a method written for it would serve a different set of workflows after every
   * release. It stays what a person reads, in the prefilled details.
   * <p>
   * What that leaves for a provider naming versions is entry 12 in the repository's GAPS.md.
   */
  private static String reportedVersionOf(
      final PeaUserTaskObservation observation) {

    return PeaTaskMeta.text(observation.taskInformation(), PeaTaskMeta.PROCESS_VERSION_TAG);

  }

  /**
   * The version the cockpit SHOWS for the task: the version tag an engine put into the meta map
   * of the delivery, or what this application deployed where it put none. A deployment key tells
   * two releases of an application apart for somebody looking at a case, which is more than
   * nothing and is why it is good enough here.
   */
  private String shownVersionOf(
      final PeaUserTaskObservation observation,
      final String bpmnProcessId) {

    final var tag = reportedVersionOf(observation);
    if (tag != null) {
      return tag;
    }
    return versions
        .versionOf(observation.adapterId(), observation.workflowModuleId(), bpmnProcessId);

  }

  /**
   * A workflow which the engine does not identify is identified by the aggregate the cockpit
   * shows it for. The Process-Engine-API promises no instance id, and a business case without any
   * id at all would arrive at the cockpit server as one case per event. See decision 6 in the
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
   * where it is generous, and the subscription's own key answers where it is not. The rule
   * behind that is shared with the reader of the platform's delivery log, which asks the same
   * question about a task this node never saw.
   */
  private static BpmnTaskSpec elementOf(
      final DeployedProcess process,
      final PeaUserTaskObservation observation) {

    return PeaUserTaskElements
        .elementOf(
            process, PeaTaskMeta
                .text(observation.taskInformation(), PeaTaskMeta.BPMN_TASK_ID),
            observation.taskDefinition());

  }

  private static String taskDefinitionOf(
      final PeaUserTaskObservation observation,
      final BpmnTaskSpec element) {

    return element == null
        ? observation.taskDefinition()
        : element.taskDefinition();

  }

  /**
   * Why the engine reported the task decides what the cockpit is told. A task delivered for the
   * first time is new, and a task delivered again changed: its assignee, its candidates or its
   * data. An engine which names no reason is reporting a task the cockpit has not seen either,
   * because a delivery it does not tell apart is a delivery it repeats.
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
   * under the same task id and the same reason, so the pair says which delivery a report came
   * from. It is not what makes two reports collapse. The outbox does that by the adapter, the
   * task and the kind of event, so an assignment and a change waiting at the same time are one
   * update either way.
   */
  private static String eventIdOf(
      final PeaUserTaskObservation observation) {

    return "%s#%s".formatted(
        observation.taskId(), observation.reason() == null
            ? TaskInformation.CREATE
            : observation.reason());

  }

}
