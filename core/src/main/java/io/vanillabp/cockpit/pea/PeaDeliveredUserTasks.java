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

  private final int capacity;

  private final Map<String, DeliveredUserTask> byTaskId;

  private final Map<String, Boolean> reportedWorkflows;

  /**
   * @param capacity How many user tasks a node remembers at once; the oldest is forgotten when
   *          the next one arrives
   */
  public PeaDeliveredUserTasks(
      final int capacity) {

    this.capacity = capacity;
    this.byTaskId = boundedMap(capacity);
    this.reportedWorkflows = boundedMap(capacity);

  }

  /**
   * @return How many user tasks this node remembers at once
   */
  public int capacity() {

    return capacity;

  }

  /**
   * Remembers a delivered user task, replacing what an earlier delivery of the same task said.
   *
   * @param task The task
   */
  public void remember(
      final DeliveredUserTask task) {

    synchronized (byTaskId) {
      byTaskId.put(task.reference().userTaskId(), task);
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
   * Notes that a task is gone, answering what was known about it - which is what the report about
   * its end is built from.
   * <p>
   * What was known stays known until the oldest entry makes room for a newer task. A report
   * written before the task ended is dispatched after it ended, and this BPMS cannot be asked
   * about a task twice: forgetting it here would drop the report of its creation with it.
   *
   * @param userTaskId The engine's own id of the task
   * @return What was remembered about it, or empty where this node never saw it
   */
  public Optional<DeliveredUserTask> ended(
      final String userTaskId) {

    synchronized (byTaskId) {
      final var known = byTaskId.get(userTaskId);
      if (known == null) {
        return Optional.empty();
      }
      final var ended = known.asEnded();
      byTaskId.put(userTaskId, ended);
      return Optional.of(ended);
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
   * Notes that a workflow was reported to the cockpit, so that it is reported with the first
   * user task of it rather than with every one.
   * <p>
   * Bounded like everything else here, and with the same consequence: a business case which as
   * many other cases have passed by since is reported as created a second time. A node holding
   * thousands of cases at once is the price of not paying for every case it ever saw.
   *
   * @param adapterId The configured adapter id holding the workflow
   * @param workflowId The engine's own id of the workflow
   * @return Whether this node reported it for the first time
   */
  public boolean workflowReportedForTheFirstTime(
      final String adapterId,
      final String workflowId) {

    synchronized (reportedWorkflows) {
      return reportedWorkflows
          .put(
              adapterId
                  + " "
                  + workflowId,
              Boolean.TRUE) == null;
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
