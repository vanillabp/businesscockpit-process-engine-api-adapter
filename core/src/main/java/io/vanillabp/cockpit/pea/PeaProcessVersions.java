package io.vanillabp.cockpit.pea;

import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;

/**
 * Which version of a BPMN process a workflow runs on, as far as the Process-Engine-API can say.
 * <p>
 * It cannot say much. The API has no repository, so there are no process definitions to count and
 * no version numbers. Two things do exist: the deployment key the engine answered when this
 * application version deployed its files, and a version tag an engine may put into the meta map of
 * a delivered task. This interface answers the deployment key, because a deployment knows nothing
 * else. It at least tells apart what two releases of an application deployed.
 * <p>
 * The tag arrives with the task which carried it, and {@link PeaDeliveredUserTasks} is where it
 * survives the delivery. The read of a task and the read of a business case both get it from
 * there, so the two show the tag where an engine fills it and the deployment key otherwise.
 * <p>
 * Both are only ever the CURRENTLY deployed version. A workflow still running on what an earlier
 * release deployed is shown with the version this release deployed, because the engine keeps no
 * record either. The repository's <code>GAPS.md</code> spells that out.
 */
@FunctionalInterface
public interface PeaProcessVersions {

  /**
   * Says which version of a BPMN process this application deployed.
   *
   * @param adapterId The configured adapter id which deployed the process
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   * @return The version, or <code>null</code> where this application deployed no such process
   */
  String versionOf(
      String adapterId,
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Reads the versions out of what the adapter recorded while it deployed the processes.
   *
   * @param registry What the VanillaBP Process-Engine-API adapter recorded while deploying
   * @return The versions it recorded
   */
  static PeaProcessVersions of(
      final PeaDeployedProcessesRegistry registry) {

    return (
        adapterId,
        workflowModuleId,
        bpmnProcessId) -> {
      final var deployed = registry
          .forAdapter(adapterId)
          .deployedVersionOf(workflowModuleId, bpmnProcessId);
      return deployed == null
          ? null
          : deployed.deploymentKey();
    };

  }

}
