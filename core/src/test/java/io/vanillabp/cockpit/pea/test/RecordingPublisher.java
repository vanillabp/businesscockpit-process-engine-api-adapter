package io.vanillabp.cockpit.pea.test;

import java.time.OffsetDateTime;
import java.util.LinkedList;
import java.util.List;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;

/**
 * The platform-neutral half of the extension, played by the test: it writes down what it was told
 * instead of writing an outbox entry, so that a test can tell "reported nothing" from "was never
 * asked".
 */
public class RecordingPublisher implements BusinessCockpitEventPublisher {

  private final List<UserTaskReference> userTasks = new LinkedList<>();

  private final List<UserTaskEventKind> userTaskKinds = new LinkedList<>();

  private final List<String> userTaskEventIds = new LinkedList<>();

  private final List<EventTransaction> transactions = new LinkedList<>();

  private final List<WorkflowReference> workflows = new LinkedList<>();

  private final List<WorkflowEventKind> workflowKinds = new LinkedList<>();

  private final List<String> workflowEventIds = new LinkedList<>();

  @Override
  public boolean publishUserTaskEvent(
      final UserTaskReference userTask,
      final UserTaskEventKind kind,
      final String bpmsEventId,
      final OffsetDateTime timestamp,
      final EventTransaction transaction) {

    userTasks.add(userTask);
    userTaskKinds.add(kind);
    userTaskEventIds.add(bpmsEventId);
    transactions.add(transaction);
    return true;

  }

  @Override
  public boolean publishWorkflowEvent(
      final WorkflowReference workflow,
      final WorkflowEventKind kind,
      final String bpmsEventId,
      final OffsetDateTime timestamp,
      final EventTransaction transaction) {

    workflows.add(workflow);
    workflowKinds.add(kind);
    workflowEventIds.add(bpmsEventId);
    transactions.add(transaction);
    return true;

  }

  /**
   * @return The user tasks reported so far
   */
  public List<UserTaskReference> userTasks() {

    return userTasks;

  }

  /**
   * @return What the user-task reports said happened
   */
  public List<UserTaskEventKind> userTaskKinds() {

    return userTaskKinds;

  }

  /**
   * @return The BPMS event ids the user-task reports carried
   */
  public List<String> userTaskEventIds() {

    return userTaskEventIds;

  }

  /**
   * @return Which transaction each report was written in, in the order they were reported
   */
  public List<EventTransaction> transactions() {

    return transactions;

  }

  /**
   * @return The workflows reported so far
   */
  public List<WorkflowReference> workflows() {

    return workflows;

  }

  /**
   * @return What the workflow reports said happened
   */
  public List<WorkflowEventKind> workflowKinds() {

    return workflowKinds;

  }

  /**
   * @return The BPMS event ids the workflow reports carried
   */
  public List<String> workflowEventIds() {

    return workflowEventIds;

  }

}
