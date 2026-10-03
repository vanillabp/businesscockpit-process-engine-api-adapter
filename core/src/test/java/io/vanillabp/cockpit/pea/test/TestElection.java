package io.vanillabp.cockpit.pea.test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.vanillabp.integration.extension.spi.election.WorkflowElection;

/**
 * VanillaBP's election as this extension sees it: it names the adapter of a workflow, and it
 * says which id VanillaBP wrote down when it started one.
 * <p>
 * It starts out knowing no start at all. That is the answer for every workflow started before
 * VanillaBP wrote such notes, so a test which says nothing about starts reads only the deliveries.
 */
public class TestElection implements WorkflowElection {

  private final Map<String, String> startedWorkflows = new HashMap<>();

  /**
   * Writes down the start of a workflow, the way VanillaBP does once the engine answered the
   * start with its own id of the instance.
   *
   * @param workflowAggregateId The business case
   * @param workflowId The engine's own id of the workflow it started
   */
  public void started(
      final String workflowAggregateId,
      final String workflowId) {

    startedWorkflows.put(keyOf(TestModels.MODULE_ID, TestModels.BPMN_PROCESS_ID, workflowAggregateId), workflowId);

  }

  @Override
  public String adapterIdOfWorkflow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return TestModels.ADAPTER_ID;

  }

  @Override
  public Optional<String> workflowIdOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return Optional
        .ofNullable(startedWorkflows.get(keyOf(workflowModuleId, bpmnProcessId, workflowAggregateId)));

  }

  private static String keyOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return "%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, workflowAggregateId);

  }

}
