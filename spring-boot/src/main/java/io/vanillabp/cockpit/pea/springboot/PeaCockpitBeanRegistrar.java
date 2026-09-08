package io.vanillabp.cockpit.pea.springboot;

import java.util.TreeSet;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.pea.PeaCockpitBridge;
import io.vanillabp.cockpit.pea.PeaCockpitSettings;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks;
import io.vanillabp.cockpit.pea.PeaProcessVersions;
import io.vanillabp.cockpit.pea.PeaWorkflowModels;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.pea.PeaAdapter;

/**
 * Registers one Business Cockpit bridge per configured Process-Engine-API adapter id.
 * <p>
 * The Process-Engine-API adapter refuses a second adapter id of its type today, so there is one
 * of them; the shape stays the one every BPMS half has, because the cockpit addresses a workflow
 * by the BPMS holding it and a migration is exactly the case where that is more than one. How
 * many there are is decided by the configuration, which is why the beans are registered
 * programmatically; they are element beans and never a bean of type <code>List</code>, because
 * that is how the cockpit's neutral half collects them on Spring Boot.
 */
public class PeaCockpitBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    processEngineApiAdapterIds(environment)
        .forEach(
            adapterId -> registry
                .registerBean(
                    "BusinessCockpit_ProcessEngineApi_Bridge_%s".formatted(adapterId),
                    BusinessCockpitBpmsBridge.class,
                    spec -> spec
                        .supplier(
                            supplierContext -> new PeaCockpitBridge(
                                adapterId, supplierContext.bean(PeaWorkflowModels.class), supplierContext
                                    .bean(PeaDeliveredUserTasks.class), supplierContext
                                        .bean(PeaProcessVersions.class), PeaCockpitSettings
                                            .rememberedUserTasks(
                                                supplierContext
                                                    .bean(MigrationAdapterProperties.class))))));

  }

  /**
   * The adapter ids always come from the platform's own configuration rather than from the
   * adapter's overlay map, the same rule the adapter itself follows: an environment variable can
   * materialize an overlay entry for an adapter nobody configured.
   */
  private static Iterable<String> processEngineApiAdapterIds(
      final Environment environment) {

    final var properties = Binder
        .get(environment)
        .bind(MigrationAdapterProperties.PREFIX, Bindable.of(MigrationAdapterProperties.class))
        .orElseGet(MigrationAdapterProperties::new);

    final var adapterIds = new TreeSet<String>();
    properties
        .adapterTypes()
        .forEach((
            adapterId,
            adapterType) -> {
          if (PeaAdapter.ADAPTER_TYPE.equals(adapterType)) {
            adapterIds.add(adapterId);
          }
        });
    return adapterIds;

  }

}
