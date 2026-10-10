package io.vanillabp.cockpit.pea;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.election.WorkflowStart;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;

/**
 * Which business case a user task delivered by the Process-Engine-API belongs to.
 * <p>
 * The cockpit shows business cases. A called process which shares the workflow aggregate of its
 * caller is a step of the caller's case. A called process with a workflow aggregate of its own is
 * a case of its own. See decision 21 in the repository's DECISIONS.md. Camunda 7 and Camunda 8 read
 * the call hierarchy for this. The Process-Engine-API offers no such hierarchy: a delivery names
 * the process instance the task sits in, and nothing above it.
 * <p>
 * So the caller is found with two things VanillaBP knows:
 * <ul>
 * <li>Which declared processes share the task's workflow aggregate. The core answers it
 * (<code>WorkflowTaskWiring#workflowsShareTheWorkflowAggregate</code>), the same answer the Camunda
 * 7 and Camunda 8 adapters use for their call activities. Nothing is decided here. A process which
 * shares its aggregate with no other declared process is a case of its own.</li>
 * <li>Which instance VanillaBP started for the aggregate. That is the note of the start, read under
 * the first process which shares the aggregate, in the order the application declared them. The
 * primary process of a workflow service comes first, so that is the process whose start row
 * exists. Where the task sits in that very instance, the task belongs to it. Where it sits in
 * another one, it sits in a called process, and its case is the instance of the note.</li>
 * </ul>
 * Where the engine names no process instance, or VanillaBP wrote no note, nothing can tell the two
 * apart. Then the task is filed under the instance it sits in, which is what every task got before
 * decision 21.
 * <p>
 * What was found is remembered per process instance, because each delivery would read the note
 * again. A called instance does not change its caller.
 * <p>
 * A task which is no step of a called process is checked against the note of its own process as
 * well. The Process-Engine-API promises that the id a start answers with is the id the tasks of
 * that instance name. Where the note names another instance, the engine breaks that promise, and
 * the cockpit would show the case under two ids. That is said once per instance, with the first
 * task of it. See decision 22 in the repository's DECISIONS.md.
 */
public class PeaBusinessCases {

  private static final Logger logger = LoggerFactory.getLogger(PeaBusinessCases.class);

  /** How many instances are remembered before the oldest is dropped. */
  private static final int REMEMBERED_INSTANCES = 1_000;

  /**
   * The case a task belongs to.
   *
   * @param workflowId The engine's id of the instance which is the case
   * @param bpmnProcessId The process of that instance, as the application wrote it
   * @param processVersion The version VanillaBP wrote down for it, or <code>null</code>
   */
  public record BusinessCase(
                             String workflowId,
                             String bpmnProcessId,
                             String processVersion) {
  }

  private final WorkflowTaskWiring workflowTaskWiring;

  private final ExtensionHandlers handlers;

  private final WorkflowElection election;

  private final Map<String, BusinessCase> casesByInstance = new LinkedHashMap<>(16, 0.75f, false) {

    private static final long serialVersionUID = 1L;

    @Override
    protected boolean removeEldestEntry(
        final Map.Entry<String, BusinessCase> eldest) {

      return size() > REMEMBERED_INSTANCES;

    }

  };

  /**
   * Builds the lookup on the three answers of the core it needs.
   *
   * @param workflowTaskWiring The core, which answers which processes share a workflow aggregate
   * @param handlers The core's list of the processes the application declares
   * @param election The core's note of the instance VanillaBP started for an aggregate
   */
  public PeaBusinessCases(
      final WorkflowTaskWiring workflowTaskWiring,
      final ExtensionHandlers handlers,
      final WorkflowElection election) {

    this.workflowTaskWiring = Objects.requireNonNull(workflowTaskWiring, "workflowTaskWiring");
    this.handlers = Objects.requireNonNull(handlers, "handlers");
    this.election = Objects.requireNonNull(election, "election");

  }

  /**
   * The case of one delivered task, where it is another instance than the one the task sits in.
   *
   * @param adapterId The adapter which delivered the task
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The process the task sits in
   * @param workflowAggregateId The aggregate the delivery named
   * @param processInstanceId The instance the task sits in, or <code>null</code> where the engine
   *          named none
   * @return The case of the caller, or empty where the task's own instance is its case
   */
  public Optional<BusinessCase> callerOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String processInstanceId) {

    if (processInstanceId == null) {
      return Optional.empty();
    }
    final var key = "%s|%s".formatted(adapterId, processInstanceId);
    synchronized (casesByInstance) {
      if (casesByInstance.containsKey(key)) {
        return Optional.ofNullable(casesByInstance.get(key));
      }
    }
    final var found = theCaseOf(adapterId, workflowModuleId, bpmnProcessId, workflowAggregateId, processInstanceId);
    if (found == null) {
      // the task is not a step of a called process, so its instance should be the started one
      checkTheStartNamesTheSameInstance(
          adapterId, workflowModuleId, bpmnProcessId, workflowAggregateId, processInstanceId);
    }
    synchronized (casesByInstance) {
      casesByInstance.put(key, found);
    }
    return Optional.ofNullable(found);

  }

  private BusinessCase theCaseOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String processInstanceId) {

    final var caller = handlers
        .bpmnProcessesOf(workflowModuleId)
        .stream()
        .filter(other -> !other.equals(bpmnProcessId))
        .filter(
            other -> workflowTaskWiring
                .workflowsShareTheWorkflowAggregate(workflowModuleId, other, bpmnProcessId))
        .findFirst();
    if (caller.isEmpty()) {
      // a process with an aggregate of its own: no caller can share its case
      return null;
    }
    final var start = election
        .workflowStartOf(workflowModuleId, caller.get(), workflowAggregateId)
        .filter(written -> (written.adapterId() == null) || written.adapterId().equals(adapterId))
        .filter(written -> written.workflowId() != null);
    if (start.isEmpty()) {
      logger
          .debug(
              "Process-Engine-API[{}]: VanillaBP wrote down no start of aggregate '{}' of '{}/{}', so user tasks of process instance '{}' are reported under that instance",
              adapterId, workflowAggregateId, workflowModuleId, caller.get(), processInstanceId);
      return null;
    }
    if (start.get().workflowId().equals(processInstanceId)) {
      // the task sits in the instance VanillaBP started, which is the case itself
      return null;
    }
    return new BusinessCase(start.get().workflowId(), caller.get(), start.get().processVersion());

  }

  /**
   * Says that the start of a workflow and a task of it name two different instances.
   * <p>
   * It is called once per instance, for a task which is no step of a called process. Where
   * VanillaBP wrote down no start of the task's own process, or the engine named no instance in
   * the task, there is nothing to compare. A called process VanillaBP never started has no note
   * of its own, so it is never compared.
   */
  private void checkTheStartNamesTheSameInstance(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String processInstanceId) {

    final var started = election
        .workflowStartOf(workflowModuleId, bpmnProcessId, workflowAggregateId)
        .filter(written -> (written.adapterId() == null) || written.adapterId().equals(adapterId))
        .map(WorkflowStart::workflowId)
        .filter(Objects::nonNull);
    if (started.isEmpty() || started.get().equals(processInstanceId)) {
      return;
    }
    logger
        .warn(
            """
                Process-Engine-API[{}]: the engine started the workflow of aggregate '{}' (BPMN \
                process '{}' of workflow module '{}') as process instance '{}', but it delivers a \
                user task of that workflow as one of process instance '{}'. The Process-Engine-API \
                promises that both are the same id. The cockpit shows the case under the id of \
                the task, and a change reported while the case has no open user task goes out \
                under the id of the start, which the cockpit does not know. Ask the maintainers of \
                the engine adapter to name the same process instance in both places. This is also \
                said where VanillaBP started a second workflow for the same aggregate while a task \
                of the first one is still open.""",
            adapterId,
            workflowAggregateId,
            bpmnProcessId,
            workflowModuleId,
            started.get(),
            processInstanceId);

  }

}
