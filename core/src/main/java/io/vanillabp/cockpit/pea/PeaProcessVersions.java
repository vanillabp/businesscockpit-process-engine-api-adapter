package io.vanillabp.cockpit.pea;

import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;

/**
 * Which version of a BPMN process a workflow runs on, as far as the Process-Engine-API can say.
 * <p>
 * It cannot say much. The API has no repository, so there are no process definitions to count
 * and no version numbers; what exists is the deployment key the engine answered when this
 * application version deployed its files, and a version tag an engine may put into the meta map
 * of a delivered task. The cockpit shows the tag where an engine fills it and the deployment key
 * otherwise, which at least distinguishes what two releases of an application deployed.
 * <p>
 * Both are only ever the CURRENTLY deployed version: a workflow still running on what an earlier
 * release deployed is shown with the version this one deployed, because the engine keeps no
 * record either, which the repository's <code>GAPS.md</code> spells out.
 */
@FunctionalInterface
public interface PeaProcessVersions {

  /**
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
