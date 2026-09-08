package io.vanillabp.cockpit.pea.quarkus.it;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.config.ConfigurationKeys;
import io.vanillabp.cockpit.pea.PeaCockpitSettings;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * That a number nobody can use is read while the application starts, on Quarkus too.
 * <p>
 * A CDI producer runs when somebody first asks for what it produces, so without a reader at
 * startup the typo would surface on the engine's delivery thread on a working day. The extension
 * therefore reads the setting on Quarkus' own startup signal, and this test is what says so.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class PeaCockpitConfigurationBootTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(
          jar -> jar
              .addAsResource("business-cockpit.yaml", "application.yaml")
              .addAsResource("pea-cockpit/processes/taxi-ride.bpmn")
              .addAsResource(
                  "workflow-module-descriptor/workflow-module", "META-INF/workflow-module")
              .addClass(TestAggregate.class)
              .addClass(TestAggregatePersistence.class)
              .addClass(TestWorkflowService.class)
              .addClass(CockpitServer.class))
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.rest.base-url", CockpitServer.baseUrl())
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.%s"
              .formatted(PeaCockpitSettings.REMEMBERED_USER_TASKS),
          "plenty")
      .assertException(throwable -> {
        final var messages = new StringBuilder();
        for (var cause = throwable; cause != null; cause = cause.getCause()) {
          messages
              .append(cause.getMessage())
              .append('\n');
        }
        Assertions
            .assertTrue(
                messages
                    .toString()
                    .contains(
                        ConfigurationKeys
                            .globalKey(PeaCockpitSettings.REMEMBERED_USER_TASKS)),
                "the boot ends naming the key to fix: "
                    + messages);
      });

  @Test
  @DisplayName("A number nobody can use ends the boot naming the key which holds it")
  public void anUnusableNumberEndsTheBoot() {
    // the expected startup failure is asserted above, so this never runs
  }

}
