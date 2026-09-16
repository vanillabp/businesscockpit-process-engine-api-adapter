package io.vanillabp.cockpit.pea.springboot;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

import io.vanillabp.cockpit.extension.config.CockpitSettings;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.pea.PeaCockpitBridge;
import io.vanillabp.cockpit.pea.PeaCockpitSettings;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks;
import io.vanillabp.cockpit.pea.PeaProcessVersions;
import io.vanillabp.cockpit.pea.PeaRecordedUserTasks;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;

/**
 * Registers one Business Cockpit bridge per configured Process-Engine-API adapter id.
 * <p>
 * The Process-Engine-API adapter refuses a second adapter id of its type today, so there is one
 * of them. The shape stays the one every BPMS half has, because the cockpit addresses a workflow
 * by the BPMS holding it, and a migration is exactly the case where that is more than one. How
 * many there are is decided by the configuration, which is why the beans are registered
 * programmatically. They are element beans and never a bean of type <code>List</code>, because
 * that is how the cockpit's neutral half collects them on Spring Boot.
 * <p>
 * WHICH adapter ids those are is the platform's answer
 * ({@code AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}), the same one the
 * Process-Engine-API adapter registers its own beans for. Filtering the configured types is not
 * that answer. An id named in <code>prioritized-adapters</code> needs no section of its own, and
 * an application which configured nothing at all has the id the classpath derives. On this BPMS
 * that is the everyday case, because an application takes a single adapter dependency.
 */
public class PeaCockpitBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    AdapterBeanRegistrarSupport
        .forEachConfiguredAdapterId(
            environment,
            PeaAdapter.ADAPTER_TYPE,
            adapterId -> registry
                .registerBean(
                    "BusinessCockpit_ProcessEngineApi_Bridge_%s".formatted(adapterId),
                    BusinessCockpitBpmsBridge.class,
                    spec -> spec
                        .supplier(
                            supplierContext -> new PeaCockpitBridge(
                                adapterId, supplierContext.bean(PeaDeployedProcessesRegistry.class), supplierContext
                                    .bean(PeaDeliveredUserTasks.class), supplierContext
                                        .bean(PeaRecordedUserTasks.class), supplierContext
                                            .bean(PeaProcessVersions.class), PeaCockpitSettings
                                                .rememberedUserTasks(
                                                    supplierContext.bean(CockpitSettings.class))))));

  }

}
