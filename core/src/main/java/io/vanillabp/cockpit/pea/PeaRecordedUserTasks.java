package io.vanillabp.cockpit.pea;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;

/**
 * The user tasks VanillaBP wrote down while it handed them to the application.
 * <p>
 * The platform keeps a delivery log in the application's own database. It writes one record per
 * delivery it processed, in the transaction which saves the workflow aggregate, and it stamps
 * the record once the completion of that task reached the BPMS. That log outlives a restart and
 * it is read by every node, which is exactly what the memory of this node cannot do. So this
 * half reads it for what it carries: that a task was delivered, with which outcome it ended, and
 * when.
 * <p>
 * What it does NOT carry is what the engine said about the task. A record holds identifiers and
 * an outcome, and not one field of the memory: no name, no assignee, no candidates, no dates, no
 * variables. So this is a second source next to the memory and not a replacement for it. Which
 * of the two answers where both can is decision 9 in the repository's DECISIONS.md.
 * <p>
 * Two tasks are missing from the log by design. A user task no <code>&#64;WorkflowTask</code>
 * method of the application claims is never recorded, because a record carries the outcome of a
 * delivery and nobody processed that delivery. And a task which the BPMS took away without the
 * application completing it keeps an open record, because the stamp is written when a completion
 * reaches the BPMS. Both are said again in the repository's GAPS.md.
 */
public class PeaRecordedUserTasks {

  private final ExtensionHandlers handlers;

  private final TaskDeliveryLogResolver deliveryLogs;

  private final PeaDeployedProcessesRegistry deployedProcesses;

  /**
   * @param handlers VanillaBP's answer to which workflow aggregate serves a BPMN process, which
   *          is what the log of that aggregate is resolved by
   * @param deliveryLogs The platform's resolver, which says which store holds the records of an
   *          aggregate
   * @param deployedProcesses What the adapter deployed, one record per configured adapter id
   */
  public PeaRecordedUserTasks(
      final ExtensionHandlers handlers,
      final TaskDeliveryLogResolver deliveryLogs,
      final PeaDeployedProcessesRegistry deployedProcesses) {

    this.handlers = Objects.requireNonNull(handlers, "handlers");
    this.deliveryLogs = Objects.requireNonNull(deliveryLogs, "deliveryLogs");
    this.deployedProcesses = Objects.requireNonNull(deployedProcesses, "deployedProcesses");

  }

  /**
   * The open user tasks of one business case, as the log holds them.
   *
   * @param adapterId The configured adapter id asking
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   * @param workflowAggregateId The aggregate's id, serialized
   * @return The tasks, oldest first, empty where the log holds none or there is no log
   */
  public List<UserTaskReference> openTasksOfAggregate(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return logOf(workflowModuleId, bpmnProcessId)
        .map(
            log -> log
                .openTasksOfAggregate(workflowModuleId, bpmnProcessId, workflowAggregateId))
        .orElseGet(List::of)
        .stream()
        .filter(record -> deliveredBy(record, adapterId))
        .map(record -> referenceOf(adapterId, record))
        .toList();

  }

  /**
   * One open user task of one business case, as the log holds it.
   *
   * @param adapterId The configured adapter id asking
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   * @param workflowAggregateId The aggregate's id, serialized
   * @param userTaskId The engine's own id of the task
   * @return The task, empty where the log does not hold it, holds it as closed, or there is no
   *         log
   */
  public Optional<UserTaskReference> openTaskOfAggregate(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String userTaskId) {

    return logOf(workflowModuleId, bpmnProcessId)
        .flatMap(
            log -> log
                .recordOfTask(workflowModuleId, bpmnProcessId, workflowAggregateId, userTaskId))
        .filter(record -> deliveredBy(record, adapterId))
        // the stamp is what takes a task out of the open answer, and a record without one is
        // still open
        .filter(record -> record.taskClosedAt() == null)
        .map(record -> referenceOf(adapterId, record));

  }

  /**
   * The store holding the records of the aggregate behind a BPMN process.
   * <p>
   * Which store that is follows the persistence VanillaBP resolved for the aggregate, so the
   * platform is asked rather than guessed at. An application which configured no store at all
   * gets no answer here, and that stays harmless: this half then knows what this node saw, which
   * is what it knew before the log existed.
   */
  private Optional<TaskDeliveryLog> logOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return handlers
        .workflowAggregateOf(workflowModuleId, bpmnProcessId)
        .map(deliveryLogs::resolveFor);

  }

  /**
   * Whether a record was written for the engine this bridge serves. One store holds the records
   * of every configured adapter id, and a task of another engine answered under this one would
   * send the cockpit to the wrong BPMS. A record naming no adapter at all was written before
   * that field existed, and it could belong to any of them, so it is not answered either.
   */
  private static boolean deliveredBy(
      final TaskDelivery record,
      final String adapterId) {

    return adapterId.equals(record.adapterId());

  }

  /**
   * How the cockpit addresses the task a record is about.
   * <p>
   * Two of the eight identifiers are not in the record on this BPMS. The Process-Engine-API
   * adapter names neither the BPMN element of a delivery nor the engine's own id of the
   * workflow, so the record carries neither, and both are answered the way a delivery answers
   * them: the element out of what the adapter deployed, and the workflow by the aggregate it is
   * shown for (decision 6 in the repository's DECISIONS.md). An adapter which does name them
   * wins, because the record is read first.
   */
  private UserTaskReference referenceOf(
      final String adapterId,
      final TaskDelivery record) {

    final var process = deployedProcesses
        .forAdapter(adapterId)
        .deployedVersionOf(record.workflowModuleId(), record.bpmnProcessId());
    final var element = process == null
        ? null
        : PeaUserTaskElements
            .elementOf(process, record.bpmnElementId(), record.taskDefinition());
    final var workflowId = record.workflowId() == null
        ? record.workflowAggregateId()
        : record.workflowId();
    final var taskDefinition = element == null
        ? record.taskDefinition()
        : element.taskDefinition();
    final var bpmnElementId = record.bpmnElementId() != null
        ? record.bpmnElementId()
        : elementIdOf(element);
    return new UserTaskReference(
        adapterId, record.workflowModuleId(), record.bpmnProcessId(), record
            .workflowAggregateId(), workflowId, record.taskId(), taskDefinition, bpmnElementId);

  }

  private static String elementIdOf(
      final BpmnTaskSpec element) {

    return element == null
        ? null
        : element.activityId();

  }

}
