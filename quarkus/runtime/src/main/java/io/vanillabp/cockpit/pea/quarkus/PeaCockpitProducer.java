package io.vanillabp.cockpit.pea.quarkus;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;
import io.vanillabp.cockpit.extension.config.CockpitSettings;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.pea.PeaCockpitBridge;
import io.vanillabp.cockpit.pea.PeaCockpitObserver;
import io.vanillabp.cockpit.pea.PeaCockpitSettings;
import io.vanillabp.cockpit.pea.PeaCockpitWiring;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks;
import io.vanillabp.cockpit.pea.PeaProcessVersions;
import io.vanillabp.cockpit.pea.PeaWorkflowModels;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Registers the Process-Engine-API half of the Business Cockpit extension on Quarkus - the twin
 * of the Spring Boot module's auto-configuration, doing the same things with CDI.
 * <p>
 * The producers are <code>&#64;Singleton</code> rather than
 * <code>&#64;ApplicationScoped</code>: what they produce has no no-argument constructor and is
 * therefore not client-proxyable.
 */
@ApplicationScoped
public class PeaCockpitProducer {

  /**
   * Reads this half's setting while the application starts.
   * <p>
   * A CDI producer runs when somebody first asks for what it produces, which for the memory of
   * the deliveries is the first delivered user task - a working day later, and on the engine's
   * own thread. So the value is read here as well, where a number nobody can use ends the boot
   * the way it does on Spring Boot.
   *
   * @param startup Quarkus' own signal that the application is starting
   * @param settings What the application wrote below the cockpit's own sections
   */
  void readTheSettingsWhileTheApplicationStarts(
      @Observes final StartupEvent startup,
      final CockpitSettings settings) {

    PeaCockpitSettings.rememberedUserTasks(settings);

  }

  /**
   * @return What this application deployed to the Process-Engine-API, shared by the wiring
   *         service filling it and by everything which needs a name or a BPMN element the engine
   *         does not report
   */
  @Produces
  @Singleton
  @Unremovable
  public PeaWorkflowModels businessCockpitPeaWorkflowModels() {

    return new PeaWorkflowModels();

  }

  /**
   * @param models The deployed models
   * @return This extension's place in VanillaBP's deployment pipeline, taken for a workflow
   *         module which runs on the Process-Engine-API and for no other
   */
  @Produces
  @Singleton
  @Unremovable
  public ExtensionWiringService<PeaBpmnModel, PeaProcessingContext> businessCockpitPeaWiringService(
      final PeaWorkflowModels models) {

    return new PeaCockpitWiring(models);

  }

  /**
   * The memory of what a delivered user task said, sized by the application. The configured
   * value is read while the application starts, so a number nobody can use is a message on the
   * first boot.
   *
   * @param settings What the application wrote below the cockpit's own sections
   * @return The memory
   */
  @Produces
  @Singleton
  @Unremovable
  public PeaDeliveredUserTasks businessCockpitPeaDeliveredUserTasks(
      final CockpitSettings settings) {

    return new PeaDeliveredUserTasks(PeaCockpitSettings.rememberedUserTasks(settings));

  }

  /**
   * @param registry What the Process-Engine-API adapter recorded while deploying
   * @return The versions of the deployed processes
   */
  @Produces
  @Singleton
  @Unremovable
  public PeaProcessVersions businessCockpitPeaProcessVersions(
      final PeaDeployedProcessesRegistry registry) {

    return PeaProcessVersions.of(registry);

  }

  /**
   * Where a user task the Process-Engine-API delivered is handed to the cockpit.
   *
   * @param models The deployed models
   * @param deliveredUserTasks The memory of what a delivery said
   * @param scoping VanillaBP's name-clash avoidance
   * @param versions The versions of the deployed processes
   * @param publisher Where an observed event is reported, resolved on the first event rather
   *          than now
   * @return The observer
   */
  @Produces
  @Singleton
  @Unremovable
  public PeaCockpitObserver businessCockpitPeaUserTaskObserver(
      final PeaWorkflowModels models,
      final PeaDeliveredUserTasks deliveredUserTasks,
      final NameClashAvoidanceSupport scoping,
      final PeaProcessVersions versions,
      final Instance<BusinessCockpitEventPublisher> publisher) {

    return new PeaCockpitObserver(
        models, deliveredUserTasks, scoping, versions, publisher::get);

  }

  /**
   * One bridge per configured Process-Engine-API adapter id.
   * <p>
   * They are produced as one list rather than as one bean each: how many there are is decided by
   * the configuration, which a producer method cannot express. The cockpit's neutral half
   * collects both shapes, the same way VanillaBP's own Quarkus integration collects the
   * deployment services of a BPMS adapter.
   *
   * @param properties VanillaBP's resolved configuration, which names the configured adapters
   * @param settings What the application wrote below the cockpit's own sections
   * @param models The deployed models
   * @param deliveredUserTasks The memory of what a delivery said
   * @param versions The versions of the deployed processes
   * @return One bridge per configured Process-Engine-API adapter id
   */
  @Produces
  @Singleton
  @Unremovable
  public List<BusinessCockpitBpmsBridge> businessCockpitPeaBridges(
      final MigrationAdapterProperties properties,
      final CockpitSettings settings,
      final PeaWorkflowModels models,
      final PeaDeliveredUserTasks deliveredUserTasks,
      final PeaProcessVersions versions) {

    final var rememberedUserTasks = PeaCockpitSettings.rememberedUserTasks(settings);
    return processEngineApiAdapterIds(properties)
        .stream()
        .<BusinessCockpitBpmsBridge>map(
            adapterId -> new PeaCockpitBridge(
                adapterId, models, deliveredUserTasks, versions, rememberedUserTasks))
        .toList();

  }

  /**
   * The adapter ids always come from the platform's own configuration rather than from the
   * adapter's overlay map, the same rule the adapter itself follows: an environment variable can
   * materialize an overlay entry for an adapter nobody configured.
   */
  private static TreeSet<String> processEngineApiAdapterIds(
      final MigrationAdapterProperties properties) {

    final var adapterIds = new TreeSet<String>();
    properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> PeaAdapter.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .forEach(adapterIds::add);
    return adapterIds;

  }

}
