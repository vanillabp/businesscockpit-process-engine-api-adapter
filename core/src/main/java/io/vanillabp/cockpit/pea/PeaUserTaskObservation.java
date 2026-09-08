package io.vanillabp.cockpit.pea;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.bpmcrafters.processengineapi.task.TaskInformation;

/**
 * One user task of one workflow module, as the Process-Engine-API delivered it to the VanillaBP
 * adapter's subscription.
 * <p>
 * The four identifiers in front are what the adapter resolved while routing the delivery: which
 * of its configured engines the task came from, which workflow module and BPMN process the task
 * belongs to, which subscription delivered it, and which workflow aggregate the payload named.
 * Everything else is the engine's own word, handed on unchanged, because the meta map is
 * engine-specific and this extension reads more of it than the adapter needs.
 * <p>
 * A termination carries the same record with an empty payload and, on most engines, an almost
 * empty meta map: what is known about a terminated task is what was known when it was delivered,
 * which is why {@link PeaDeliveredUserTasks} remembers it.
 *
 * @param adapterId The configured adapter id whose engine delivered this task
 * @param workflowModuleId The workflow module the BPMN process belongs to
 * @param bpmnProcessId The BPMN process id, as the engine reported it - scoped where the
 *          workflow module is deployed with <code>use-prefix</code>
 * @param taskDefinition The subscription's task description key, scoped the same way
 * @param workflowAggregateId The workflow aggregate's id, serialized, or <code>null</code> where
 *          the delivery carried none (a termination does)
 * @param taskInformation What the engine says about the task: its id and its meta map
 * @param payload The variables the subscription asked the engine for, empty for a termination
 */
public record PeaUserTaskObservation(
                                     String adapterId,
                                     String workflowModuleId,
                                     String bpmnProcessId,
                                     String taskDefinition,
                                     String workflowAggregateId,
                                     TaskInformation taskInformation,
                                     Map<String, Object> payload) {

  public PeaUserTaskObservation {
    // not Map.copyOf: a process variable an engine holds as null is a value like any other,
    // and the cockpit passes it on to a '@TaskParam' parameter as null
    payload = payload == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }

  /**
   * @return The engine's own id of the user task
   */
  public String taskId() {

    return taskInformation.getTaskId();

  }

  /**
   * @return What the engine says about the task, never <code>null</code>
   */
  public Map<String, String> meta() {

    return taskInformation.getMeta() == null
        ? Map.of()
        : taskInformation.getMeta();

  }

  /**
   * @return Why the engine reported the task - <code>create</code>, <code>assign</code>,
   *         <code>update</code>, <code>complete</code> or <code>delete</code> - or
   *         <code>null</code> where the engine names no reason
   */
  public String reason() {

    return meta().get(TaskInformation.REASON);

  }

}
