package io.vanillabp.cockpit.pea;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;

/**
 * The user tasks this node has seen, and what the engine said about them.
 * <p>
 * Every other BPMS half of the Business Cockpit reads the current state of a task when the
 * report is dispatched, a moment after the event was observed. On the Process-Engine-API there
 * is nothing to read from: no task query, no single-task get, no history. What is known about a
 * task is what arrived with its delivery, so it is kept until the task is gone.
 * <p>
 * Kept in memory, per node, and bounded: an outbox entry is dispatched on the node which wrote
 * it and within seconds, so the memory of a delivery only has to outlive that. What that costs
 * is stated rather than hidden - a node which restarts between a delivery and its dispatch
 * reports the task without its details, and a task delivered to another node is unknown here.
 * Persisting it instead would make the extension keep a second copy of the engine's state, which
 * is exactly what an application's own database is for. This is decision 3 in the repository's
 * DECISIONS.md.
 */
public class PeaDeliveredUserTasks {

  /**
   * One user task the engine delivered.
   *
   * @param reference How the cockpit addresses the task
   * @param details What the engine said about it
   * @param ended Whether the engine has taken the task away again. Such a task is no longer one
   *          of its business case's open tasks, and it is still answered to the dispatch of the
   *          reports about it: those are written before the task ended and read afterwards
   */
  public record DeliveredUserTask(
                                  UserTaskReference reference,
                                  UserTaskDetailsPrefill details,
                                  boolean ended) {

    /**
     * @param reference How the cockpit addresses the task
     * @param details What the engine said about it
     */
    public DeliveredUserTask(
        final UserTaskReference reference,
        final UserTaskDetailsPrefill details) {

      this(reference, details, false);

    }

    DeliveredUserTask asEnded() {

      return new DeliveredUserTask(reference, details, true);

    }

  }

  private final Map<String, DeliveredUserTask> byTaskId;

  private final Map<String, Boolean> reportedWorkflows;

  /**
   * @param capacity How many user tasks a node remembers at once; the oldest is forgotten when
   *          the next one arrives
   */
  public PeaDeliveredUserTasks(
      final int capacity) {

    this.byTaskId = boundedMap(capacity);
    this.reportedWorkflows = boundedMap(capacity);

  }

  /**
   * Remembers a delivered user task, replacing what an earlier delivery of the same task said.
   *
   * @param task The task
   */
  public void remember(
      final DeliveredUserTask task) {

    synchronized (byTaskId) {
      putAsTheNewest(task.reference().userTaskId(), task);
    }

  }

  /**
   * @param userTaskId The engine's own id of the task
   * @return What was remembered about it, or empty where this node never saw it or has
   *         forgotten it
   */
  public Optional<DeliveredUserTask> of(
      final String userTaskId) {

    synchronized (byTaskId) {
      return Optional.ofNullable(byTaskId.get(userTaskId));
    }

  }

  /**
   * Notes that a task is gone. Called once the end was reported, so that a report which never
   * left leaves the memory as it was: a task still marked open is reported again when the engine
   * says so a second time, while one marked ended by a report nobody received would be silently
   * dropped.
   * <p>
   * What was known stays known until the oldest entry makes room for a newer task. A report
   * written before the task ended is dispatched after it ended, and this BPMS cannot be asked
   * about a task twice: forgetting it here would drop the report of its creation with it. It
   * moves to the newest end of the memory for the same reason - the reports about it are still
   * on their way.
   *
   * @param userTaskId The engine's own id of the task
   */
  public void ended(
      final String userTaskId) {

    synchronized (byTaskId) {
      final var known = byTaskId.get(userTaskId);
      if (known == null) {
        return;
      }
      putAsTheNewest(userTaskId, known.asEnded());
    }

  }

  /**
   * The OPEN tasks of one workflow aggregate this node knows about - what the cockpit asks for
   * when an application reports that an aggregate changed. A task the engine has taken away is
   * not one of them any more.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   * @param workflowAggregateId The aggregate's id, serialized
   * @return The tasks, in the order they were delivered
   */
  public List<DeliveredUserTask> ofAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var tasks = new ArrayList<DeliveredUserTask>();
    synchronized (byTaskId) {
      byTaskId
          .values()
          .stream()
          .filter(task -> !task.ended())
          .filter(task -> belongsTo(task, workflowModuleId, bpmnProcessId, workflowAggregateId))
          .forEach(tasks::add);
    }
    return List.copyOf(tasks);

  }

  /**
   * Whether the cockpit was already told about a workflow, which is what makes it reported with
   * the first user task of it rather than with every one.
   * <p>
   * Bounded like everything else here, and with the same consequence: a business case which as
   * many other cases have passed by since is reported as created a second time. A node holding
   * thousands of cases at once is the price of not paying for every case it ever saw.
   *
   * @param adapterId The configured adapter id holding the workflow
   * @param workflowId The engine's own id of the workflow
   * @return Whether this node has reported it
   */
  public boolean workflowWasReported(
      final String adapterId,
      final String workflowId) {

    synchronized (reportedWorkflows) {
      return reportedWorkflows.containsKey(workflowKey(adapterId, workflowId));
    }

  }

  /**
   * Notes that the cockpit was told about a workflow. Called once the report was written, so
   * that a report which never left leaves the memory as it was and the next user task of the
   * case tries again.
   *
   * @param adapterId The configured adapter id holding the workflow
   * @param workflowId The engine's own id of the workflow
   */
  public void rememberWorkflowWasReported(
      final String adapterId,
      final String workflowId) {

    synchronized (reportedWorkflows) {
      reportedWorkflows.put(workflowKey(adapterId, workflowId), Boolean.TRUE);
    }

  }

  private static boolean belongsTo(
      final DeliveredUserTask task,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var reference = task.reference();
    return reference.workflowModuleId().equals(workflowModuleId) && reference.bpmnProcessId()
        .equals(bpmnProcessId) && reference.workflowAggregateId().equals(workflowAggregateId);

  }

  /**
   * Puts an entry at the newest end of the memory: the map forgets its eldest entry, and an
   * update of something it already holds keeps that entry's place unless it is put in again. A
   * task the engine delivers a second time is as young as one delivered for the first time, so
   * it must not be the next one to go.
   */
  private void putAsTheNewest(
      final String userTaskId,
      final DeliveredUserTask task) {

    byTaskId.remove(userTaskId);
    byTaskId.put(userTaskId, task);

  }

  private static String workflowKey(
      final String adapterId,
      final String workflowId) {

    return adapterId
        + " "
        + workflowId;

  }

  private static <V> Map<String, V> boundedMap(
      final int capacity) {

    return new LinkedHashMap<>(16, 0.75f, false) {

      private static final long serialVersionUID = 1L;

      @Override
      protected boolean removeEldestEntry(
          final Map.Entry<String, V> eldest) {

        return size() > capacity;

      }

    };

  }

}
