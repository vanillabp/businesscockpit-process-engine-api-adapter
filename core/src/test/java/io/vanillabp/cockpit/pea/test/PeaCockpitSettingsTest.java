package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.cockpit.extension.config.CockpitSettings;
import io.vanillabp.cockpit.extension.config.ConfigurationKeys;
import io.vanillabp.cockpit.pea.PeaCockpitSettings;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The one setting this half has, and what an application is told when it wrote something nobody
 * can use. The value is read while the application starts, so these messages are what a
 * developer reads on the first boot rather than in the middle of a working day.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaCockpitSettingsTest {

  private static CockpitSettings configuredWith(
      final String rememberedUserTasks) {

    return new CockpitSettings(
        null, null, null, null, null, new CockpitSettings.ProcessEngineApi(rememberedUserTasks), Map.of());

  }

  @Test
  @DisplayName("An application which configures nothing remembers the default number of tasks")
  public void nothingConfiguredIsTheDefault() {

    assertEquals(
        PeaCockpitSettings.DEFAULT_REMEMBERED_USER_TASKS,
        PeaCockpitSettings.rememberedUserTasks(CockpitSettings.none()));

  }

  @Test
  @DisplayName("What an application configured is what a node remembers")
  public void theConfiguredNumberIsUsed() {

    assertEquals(25, PeaCockpitSettings.rememberedUserTasks(configuredWith(" 25 ")));

  }

  @Test
  @DisplayName("A value which is not a number names the key it stands under")
  public void aValueWhichIsNoNumberIsRefused() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> PeaCockpitSettings.rememberedUserTasks(configuredWith("plenty")));

    assertTrue(
        refused
            .getMessage()
            .contains(ConfigurationKeys.globalKey(PeaCockpitSettings.REMEMBERED_USER_TASKS)),
        () -> "the message names the key to fix: "
            + refused.getMessage());

  }

  @Test
  @DisplayName("A value a workflow module wrote is refused, naming the key which works")
  public void aValueOfAWorkflowModuleIsRefused() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> PeaCockpitSettings
            .refuseWhatAWorkflowModuleConfigured(
                java.util.List
                    .of(
                        "vanillabp.workflow-modules.taxi-ride.cockpit.process-engine-api.remembered-user-tasks")));

    assertTrue(
        refused.getMessage().contains("vanillabp.workflow-modules.taxi-ride.cockpit"),
        () -> "the message names the value which does nothing: "
            + refused.getMessage());
    assertTrue(
        refused
            .getMessage()
            .contains(ConfigurationKeys.globalKey(PeaCockpitSettings.REMEMBERED_USER_TASKS)),
        () -> "the message names the key to move it to: "
            + refused.getMessage());

  }

  @Test
  @DisplayName("A node which remembers nothing reports nothing, so zero is refused")
  public void zeroIsRefused() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> PeaCockpitSettings.rememberedUserTasks(configuredWith("0")));

    assertTrue(
        refused
            .getMessage()
            .contains(ConfigurationKeys.globalKey(PeaCockpitSettings.REMEMBERED_USER_TASKS)),
        () -> "the message names the key to fix: "
            + refused.getMessage());

  }

}
