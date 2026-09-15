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
import io.vanillabp.cockpit.pea.PeaDeliveredUserTasks;
import io.vanillabp.cockpit.pea.PeaProcessVersions;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;

/**
 * Registers the Process-Engine-API half of the Business Cockpit extension on Spring Boot.
 * <p>
 * Nothing here decides anything. What the extension does with this BPMS is decided in the
 * platform-neutral module of this repository. This class does what only Spring can do: find the
 * beans, and put the extension's own beans where VanillaBP and the cockpit's neutral half collect
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
   * The memory of what a delivered user task said, sized by the application.
   * <p>
   * The configured value is read here, while the application starts, rather than when the first
   * task arrives. A number nobody can use is then a message on the first boot.
   *
   * @param settings What the application wrote below the cockpit's own sections
   * @param environment Where the keys of the application are read from, to find one which this
   *          half reads globally and somebody wrote per workflow module. This platform ignores a
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
   * Where a user task the Process-Engine-API delivered is handed to the cockpit. The adapter
   * finds it by its type and calls it from its own subscription, which is the only place a
   * second pair of eyes can watch a delivery on this BPMS.
   *
   * @param registry What the Process-Engine-API adapter recorded while deploying, which is where
   *          the name of a process and the BPMN element behind a form reference come from
   * @param deliveredUserTasks The memory of what a delivery said
   * @param versions The versions of the deployed processes
   * @param publisher Where an observed event is reported. It is resolved on the first event
   *          rather than now: this bean is built while the application is still wiring itself
   *          together
   * @return The observer
   */
  @Bean
  public PeaCockpitObserver businessCockpitPeaUserTaskObserver(
      final PeaDeployedProcessesRegistry registry,
      final PeaDeliveredUserTasks deliveredUserTasks,
      final PeaProcessVersions versions,
      final ObjectProvider<BusinessCockpitEventPublisher> publisher) {

    return new PeaCockpitObserver(
        registry, deliveredUserTasks, versions, publisher::getObject);

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
