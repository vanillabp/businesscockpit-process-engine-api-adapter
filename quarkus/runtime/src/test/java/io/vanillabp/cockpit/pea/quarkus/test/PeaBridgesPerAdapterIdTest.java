package io.vanillabp.cockpit.pea.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.cockpit.extension.config.CockpitSettings;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks;
import io.vanillabp.cockpit.pea.quarkus.PeaCockpitProducer;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;

/**
 * Which Process-Engine-API adapter ids get a bridge.
 * <p>
 * The answer is VanillaBP's ({@code MigrationAdapterProperties#adapterIdsOfType}). A hand-rolled
 * filter over the configured adapter TYPES misses the everyday case on this BPMS: an application
 * takes the single adapter dependency and configures no adapter section at all, so the id it runs
 * under is the one the classpath derives. The adapter then opened its subscriptions while this
 * half built no bridge, which left every report without a BPMS to ask.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaBridgesPerAdapterIdTest {

  private List<String> bridgedAdapterIds(
      final MigrationAdapterProperties properties) {

    final var deployedProcesses = new PeaDeployedProcessesRegistry();
    return new PeaCockpitProducer()
        .businessCockpitPeaBridges(
            properties, new CockpitSettings(null, null, null, null, null, null, Map.of()),
            deployedProcesses, new PeaDeliveredUserTasks(10), TestRecordedUserTasks
                .knowingNothing(deployedProcesses),
            (
                adapterId,
                workflowModuleId,
                bpmnProcessId) -> null)
        .stream()
        .map(bridge -> bridge.adapterId())
        .toList();

  }

  @Test
  @DisplayName("An application which configured nothing gets the bridge of the derived adapter id")
  public void theDerivedAdapterIdIsBridged() {

    assertEquals(
        List.of(PeaAdapter.ADAPTER_TYPE), bridgedAdapterIds(new MigrationAdapterProperties()));

  }

  @Test
  @DisplayName("An adapter id which only stands in prioritized-adapters gets a bridge")
  public void aPrioritizedAdapterIdIsBridged() {

    final var properties = new MigrationAdapterProperties();
    properties.setPrioritizedAdapters(List.of(PeaAdapter.ADAPTER_TYPE));

    assertEquals(List.of(PeaAdapter.ADAPTER_TYPE), bridgedAdapterIds(properties));

  }

  @Test
  @DisplayName("An adapter of another BPMS gets no bridge from this half")
  public void anotherBpmsIsNotBridged() {

    final var camunda7 = new AdapterConfigProperties();
    camunda7.setType("camunda7");
    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("c7", camunda7));

    assertEquals(List.of(), bridgedAdapterIds(properties));

  }

}
