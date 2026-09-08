package io.vanillabp.cockpit.pea.springboot;

import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

import io.vanillabp.cockpit.extension.config.CockpitSettings;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.pea.PeaCockpitObserver;
import io.vanillabp.cockpit.pea.PeaCockpitSettings;
import io.vanillabp.cockpit.pea.PeaCockpitWiring;
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks;
import io.vanillabp.cockpit.pea.PeaProcessVersions;
import io.vanillabp.cockpit.pea.PeaWorkflowModels;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;

/**
 * Registers the Process-Engine-API half of the Business Cockpit extension on Spring Boot.
 * <p>
 * Nothing here decides anything: what the extension does with this BPMS is decided in the
 * platform-neutral module of this repository, and this class does what only Spring can do - find
 * the beans and put the extension's own where VanillaBP and the cockpit's neutral half collect
 * them.
 * <p>
 * It runs after VanillaBP's own auto-configuration, named rather than referenced, because an
 * extension does not compile against a platform integration.
 */
@AutoConfiguration(afterName = "io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration")
@ConditionalOnBean(MigrationAdapterProperties.class)
@Import(PeaCockpitBeanRegistrar.class)
public class PeaCockpitAutoConfiguration {

  /**
   * @return What this application deployed to the Process-Engine-API, shared by the wiring
   *         service filling it and by everything which needs a name or a BPMN element the engine
   *         does not report
   */
  @Bean
  public PeaWorkflowModels businessCockpitPeaWorkflowModels() {

    return new PeaWorkflowModels();

  }

  /**
   * @param models The deployed models
   * @return This extension's place in VanillaBP's deployment pipeline, taken for a workflow
   *         module which runs on the Process-Engine-API and for no other
   */
  @Bean
  public ExtensionWiringService<PeaBpmnModel, PeaProcessingContext> businessCockpitPeaWiringService(
      final PeaWorkflowModels models) {

    return new PeaCockpitWiring(models);

  }

  /**
   * The memory of what a delivered user task said, sized by the application.
   * <p>
   * The configured value is read here, while the application starts, rather than when the first
   * task arrives: a number nobody can use is then a message on the first boot.
   *
   * @param settings What the application wrote below the cockpit's own sections
   * @param environment Where the keys of the application are read from, to find one which this
   *          half reads globally and somebody wrote per workflow module: this platform ignores a
   *          key nothing binds, so nothing else would say it
   * @return The memory
   */
  @Bean
  public PeaDeliveredUserTasks businessCockpitPeaDeliveredUserTasks(
      final CockpitSettings settings,
      final ConfigurableEnvironment environment) {

    PeaCockpitSettings.refuseWhatAWorkflowModuleConfigured(propertyNamesOf(environment));
    return new PeaDeliveredUserTasks(PeaCockpitSettings.rememberedUserTasks(settings));

  }

  /**
   * @param registry What the Process-Engine-API adapter recorded while deploying
   * @return The versions of the deployed processes
   */
  @Bean
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
   * @param publisher Where an observed event is reported. It is resolved on the first event
   *          rather than now: this bean is built while the application is still wiring itself
   *          together
   * @return The observer
   */
  @Bean
  public PeaCockpitObserver businessCockpitPeaUserTaskObserver(
      final PeaWorkflowModels models,
      final PeaDeliveredUserTasks deliveredUserTasks,
      final NameClashAvoidanceSupport scoping,
      final PeaProcessVersions versions,
      final ObjectProvider<BusinessCockpitEventPublisher> publisher) {

    return new PeaCockpitObserver(
        models, deliveredUserTasks, scoping, versions, publisher::getObject);

  }


  /**
   * @param environment The application's environment
   * @return Every property name it can enumerate
   */
  private static Iterable<String> propertyNamesOf(
      final ConfigurableEnvironment environment) {

    final var names = new LinkedHashSet<String>();
    environment
        .getPropertySources()
        .forEach(source -> {
          if (source instanceof final EnumerablePropertySource<?> enumerable) {
            names.addAll(List.of(enumerable.getPropertyNames()));
          }
        });
    return names;

  }

}
