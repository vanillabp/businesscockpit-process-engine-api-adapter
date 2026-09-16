package io.vanillabp.cockpit.pea.test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;

/**
 * A delivery log which keeps its records in a list, standing in for the store an application
 * runs. It answers the two questions this extension asks of a real store, and it answers them
 * the way the contract of {@link TaskDeliveryLog} describes: the open records of a business
 * case, oldest first, and one record of a task whether it is open or closed.
 */
public class TestDeliveryLog implements TaskDeliveryLog {

  private final List<TaskDelivery> records = new ArrayList<>();

  /**
   * Writes down a delivery which is still open, the way VanillaBP writes one while it hands a
   * user task to the application.
   *
   * @param adapterId The configured adapter id which delivered
   * @param workflowAggregateId The business case
   * @param taskId The engine's own id of the task
   * @param recordedAt When the handler ran
   */
  public void anOpenTask(
      final String adapterId,
      final String workflowAggregateId,
      final String taskId,
      final Instant recordedAt) {

    records
        .add(
            new TaskDelivery(
                taskId, adapterId, TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, workflowAggregateId,
                // the Process-Engine-API adapter names neither the workflow nor the BPMN
                // element, so a record written on this BPMS carries neither
                null, TestModels.USER_TASK_FORM, null, taskId, "COMPLETION_PENDING", null, null, recordedAt, null));

  }

  /**
   * Writes down a delivery of a task the application has completed since.
   *
   * @param adapterId The configured adapter id which delivered
   * @param workflowAggregateId The business case
   * @param taskId The engine's own id of the task
   */
  public void aClosedTask(
      final String adapterId,
      final String workflowAggregateId,
      final String taskId) {

    anOpenTask(adapterId, workflowAggregateId, taskId, Instant.now());
    final var open = records.removeLast();
    records.add(new TaskDelivery(
        open.deliveryKey(), open.adapterId(), open.workflowModuleId(), open.bpmnProcessId(), open
            .workflowAggregateId(), open.workflowId(), open.taskDefinition(), open
                .bpmnElementId(), open.taskId(), open.outcome(), null, null, open
                    .recordedAt(), Instant.now()));

  }

  @Override
  public Optional<TaskDelivery> recordedDelivery(
      final String deliveryKey) {

    return records
        .stream()
        .filter(record -> record.deliveryKey().equals(deliveryKey))
        .findFirst();

  }

  @Override
  public boolean record(
      final TaskDelivery delivery) {

    records.add(delivery);
    return true;

  }

  @Override
  public Optional<TaskDelivery> recordOfTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    return records
        .stream()
        .filter(record -> belongsTo(record, workflowModuleId, bpmnProcessId, workflowAggregateId))
        .filter(record -> taskId.equals(record.taskId()))
        .reduce((
            earlier,
            later) -> later);

  }

  @Override
  public List<TaskDelivery> openTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return records
        .stream()
        .filter(record -> belongsTo(record, workflowModuleId, bpmnProcessId, workflowAggregateId))
        .filter(record -> record.taskClosedAt() == null)
        .sorted(Comparator.comparing(TaskDelivery::recordedAt))
        .toList();

  }

  private static boolean belongsTo(
      final TaskDelivery record,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return record.workflowModuleId().equals(workflowModuleId) && record.bpmnProcessId()
        .equals(bpmnProcessId) && record.workflowAggregateId().equals(workflowAggregateId);

  }

}
