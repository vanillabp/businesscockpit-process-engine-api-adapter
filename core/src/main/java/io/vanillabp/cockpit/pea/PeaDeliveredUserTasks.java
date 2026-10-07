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
 * Every other BPMS half of the Business Cockpit can read the state of a task from its engine at
 * any time. On the Process-Engine-API there is nothing to read from: no task query, no
 * single-task get, no history. What is known about a task is what arrived with its delivery, so
 * that is kept until the task is gone.
 * <p>
 * The report of a delivered task is built while that delivery is being handled, out of what was
 * just written here, and it travels inside the outbox entry. So no report waits on this memory.
 * The questions which come later do: how a task ended, which tasks of a business case are open,
 * and what <code>getUserTask</code> shows.
 * <p>
 * It is kept in memory, per node, and bounded. What that costs is said out loud rather than
 * hidden. A node which restarts has none of it, and a task delivered to another node is unknown
 * here, so those later questions go unanswered there. Persisting it instead would make the
 * extension keep a second copy of the engine's state, which is exactly what an application's own
 * database is for. This is decision 3 in the repository's DECISIONS.md, and decision 10 says what
 * the report being built at the event took off it.
 */
public class PeaDeliveredUserTasks {

  /**
   * One user task the engine delivered.
   *
   * @param reference How the cockpit addresses the task
   * @param details What the engine said about it
   * @param ended Whether the engine has taken the task away again. Such a task is no longer one
   *          of the open tasks of its business case. What was known about it stays here, because
   *          this memory is the only source which saw the engine take the task away
   */
  public record DeliveredUserTask(
                                  UserTaskReference reference,
                                  UserTaskDetailsPrefill details,
                                  boolean ended) {

    /**
     * Remembers a task which is still open.
     *
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
   * Builds an empty memory which holds at most the given number of user tasks.
   *
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
   * Looks up what this node remembers about one user task.
   *
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
   * Notes that a task is gone. It is called once the end was reported, so that a report which
   * never got written leaves the memory as it was and the engine saying a second time that the
   * task is gone reports it again.
   * <p>
   * What was known stays here, and it keeps the place it had. It stays because the reads of a
   * business case ask this memory first, and it is the only source which saw the engine take the
   * task away: VanillaBP's delivery log holds a task until the application completes it. It keeps
   * its place because nothing is waiting for it any more. The report of the end was built before
   * this call and carries what it needs, so a task which is over must not push out one somebody
   * is still working on.
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
      // put, not putAsTheNewest: the entry keeps its age and makes room for newer tasks
      byTaskId.put(userTaskId, known.asEnded());
    }

  }

  /**
   * The OPEN tasks of one workflow aggregate this node knows about. This is what the cockpit asks
   * for when an application reports that an aggregate changed. A task the engine has taken away
   * is not one of them any more.
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
   * The version the engine named with the last user task of one workflow this node was delivered.
   * It is what the cockpit shows for the business case, so a case and its tasks say the same
   * thing. The value is the one the delivery was remembered under: the version tag where the
   * engine wrote one into the meta map of that task, and what this application deployed where it
   * wrote none.
   * <p>
   * Two things are different from {@link #ofAggregate}. A task the engine has taken away counts,
   * because the version of a business case does not change when its last task is finished.
   * Leaving ended tasks out would make the end of a case lose the tag again. And where two tasks
   * of one workflow carry different tags, because the files went out again in between, the one
   * delivered last wins. It is the later word of the engine about the same case.
   *
   * @param adapterId The configured adapter id which got the delivery
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   * @param workflowId The engine's own id of the workflow
   * @return The version, or <code>null</code> where this node holds no task of that workflow
   */
  public String versionOfWorkflow(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowId) {

    synchronized (byTaskId) {
      String version = null;
      for (final var task : byTaskId.values()) {
        if (isOfWorkflow(task, adapterId, workflowModuleId, bpmnProcessId, workflowId)) {
          // the map holds its entries in the order they were delivered, so the last match is
          // what the engine said last about this workflow
          version = task.details().bpmnProcessVersion();
        }
      }
      return version;
    }

  }

  /**
   * The version tag the engine wrote into the meta map of the last user task of one workflow
   * this node was delivered. It is the version a details provider is picked by. So it differs
   * from {@link #versionOfWorkflow} in one point: where the engine wrote no tag, the answer is
   * <code>null</code> and not what this application deployed. A deployment key picks no provider
   * which names a version.
   * <p>
   * Like in {@link #versionOfWorkflow}, a task the engine has taken away counts, and the task
   * delivered last wins.
   *
   * @param adapterId The configured adapter id which got the delivery
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   * @param workflowId The engine's own id of the workflow
   * @return The tag, or <code>null</code> where this node holds no task of that workflow or
   *         where the last one carried no tag
   */
  public String versionTagOfWorkflow(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowId) {

    synchronized (byTaskId) {
      String tag = null;
      for (final var task : byTaskId.values()) {
        if (isOfWorkflow(task, adapterId, workflowModuleId, bpmnProcessId, workflowId)) {
          tag = task.reference().processVersion();
        }
      }
      return tag;
    }

  }

  /**
   * Whether the cockpit was already told about a workflow. That is what makes a workflow reported
   * with its first user task rather than with every one.
   * <p>
   * It is bounded like everything else here, and it costs the same. A business case which many
   * other cases have passed by since is reported as created a second time. Holding thousands of
   * cases at once is what a node pays instead of paying for every case it ever saw.
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
   * Notes that the cockpit was told about a workflow. It is called once the report was written,
   * so that a report which never left leaves the memory as it was and the next user task of the
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

  /**
   * Whether a remembered task belongs to one workflow of one engine.
   * <p>
   * The workflow module and the BPMN process are part of the question, although the engine's own
   * id of a workflow should be enough on its own. That id is not always the engine's: a delivery
   * which names no workflow is remembered under the aggregate's id instead, and two aggregates of
   * two BPMN processes may well carry the same id. Without the process, one business case would
   * then answer with the version of another.
   */
  private static boolean isOfWorkflow(
      final DeliveredUserTask task,
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowId) {

    final var reference = task.reference();
    return reference.adapterId().equals(adapterId) && reference.workflowModuleId()
        .equals(workflowModuleId) && reference.bpmnProcessId().equals(bpmnProcessId) && reference
            .workflowId().equals(workflowId);

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
   * Puts an entry at the newest end of the memory. The map forgets its eldest entry, and an
   * update of something it already holds keeps that entry's place unless the entry is put in
   * again. A task the engine delivers a second time is as young as one delivered for the first
   * time, so it must not be the next one to go.
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
