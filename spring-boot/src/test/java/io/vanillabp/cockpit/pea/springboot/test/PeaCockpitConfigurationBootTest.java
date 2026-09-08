package io.vanillabp.cockpit.pea.springboot.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.cockpit.extension.config.ConfigurationKeys;
import io.vanillabp.cockpit.pea.PeaCockpitSettings;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * That a number nobody can use is read while the application starts.
 * <p>
 * The unit test of the setting shows what the message says; this one shows that somebody reads it
 * at all, which is the difference between a developer learning about the typo on the first boot
 * and a node reporting no user task on a working day.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class PeaCockpitConfigurationBootTest {

  private static void boot(
      final String... properties) {

    final var arguments = Stream
        .of(properties)
        .map("--%s"::formatted)
        .toArray(String[]::new);
    new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        .run(arguments)
        .close();

  }

  private static String messagesOf(
      final Throwable failure) {

    final var messages = new StringBuilder();
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      messages
          .append(cause.getMessage())
          .append('\n');
    }
    return messages.toString();

  }

  @Test
  @DisplayName("A number nobody can use ends the boot naming the key which holds it")
  public void anUnusableNumberEndsTheBoot() {

    final var refused = assertThrows(
        Exception.class,
        () -> boot(
            "spring.datasource.url=jdbc:h2:mem:pea-cockpit-boot-failure",
            "vanillabp.cockpit.rest.base-url=%s".formatted(
                CockpitServer.baseUrl()),
            "vanillabp.cockpit.%s=plenty"
                .formatted(PeaCockpitSettings.REMEMBERED_USER_TASKS)));

    final var messages = messagesOf(refused);
    assertTrue(
        messages
            .contains(ConfigurationKeys.globalKey(PeaCockpitSettings.REMEMBERED_USER_TASKS)),
        () -> "the boot ends naming the key to fix: "
            + messages);

  }

}
