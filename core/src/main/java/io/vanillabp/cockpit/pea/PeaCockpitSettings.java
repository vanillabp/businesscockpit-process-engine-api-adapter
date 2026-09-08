package io.vanillabp.cockpit.pea;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.cockpit.extension.config.ConfigurationKeys;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;

/**
 * What the Business Cockpit's Process-Engine-API half reads out of the application's
 * configuration, and what it says when it does not like it.
 * <p>
 * There is one setting, and it exists because this BPMS cannot be asked about a task twice: how
 * many delivered user tasks a node remembers. Everything else about the cockpit is configured
 * once for every BPMS, by the extension's platform-neutral half.
 * <p>
 * The setting is global rather than per workflow module: the memory it sizes is one per node and
 * shared by every module, so a value per module would promise a division which does not exist.
 * It is read and checked while the application starts, so a value nobody can use is a message on
 * the first boot rather than an exception in the middle of a working day.
 */
public final class PeaCockpitSettings {

  private static final Logger logger = LoggerFactory.getLogger(PeaCockpitSettings.class);

  /** How many delivered user tasks a node remembers at once. */
  public static final String REMEMBERED_USER_TASKS = "process-engine-api.remembered-user-tasks";

  /**
   * Enough for the tasks of a busy node between a delivery and the dispatch which follows it
   * seconds later, and small enough to be paid for without thinking about it.
   */
  public static final int DEFAULT_REMEMBERED_USER_TASKS = 1000;

  private PeaCockpitSettings() {
  }

  /**
   * @param properties VanillaBP's resolved configuration
   * @return How many delivered user tasks a node is to remember
   * @throws IllegalStateException If a workflow module configured the key, or if the configured
   *           value is not a number or not positive; the message names the key and the default
   */
  public static int rememberedUserTasks(
      final MigrationAdapterProperties properties) {

    final var key = ConfigurationKeys.globalKey(REMEMBERED_USER_TASKS);
    refuseWhatAWorkflowModuleConfigured(properties, key);
    final var configured = properties
        .extensionProperty(null, ConfigurationKeys.EXTENSION_ID, REMEMBERED_USER_TASKS);
    if ((configured == null) || configured.isBlank()) {
      logger
          .debug(
              "The Business Cockpit remembers {} delivered user tasks per node; set '{}' to change it",
              DEFAULT_REMEMBERED_USER_TASKS,
              key);
      return DEFAULT_REMEMBERED_USER_TASKS;
    }
    final int remembered;
    try {
      remembered = Integer.parseInt(configured.trim());
    } catch (final NumberFormatException e) {
      throw new IllegalStateException(
          """
              The Business Cockpit's Process-Engine-API half was configured with '%s: %s', which is \
              not a number. It says how many delivered user tasks one node remembers, because the \
              Process-Engine-API cannot be asked about a task a second time. Write a positive number \
              there or remove the key, which leaves it at %d."""
              .formatted(key, configured, DEFAULT_REMEMBERED_USER_TASKS), e);
    }
    if (remembered < 1) {
      throw new IllegalStateException(
          """
              The Business Cockpit's Process-Engine-API half was configured with '%s: %d'. A node \
              which remembers no user task reports none, because the Process-Engine-API cannot be \
              asked about a task a second time. Write a positive number there or remove the key, \
              which leaves it at %d."""
              .formatted(key, remembered, DEFAULT_REMEMBERED_USER_TASKS));
    }
    return remembered;

  }

  /**
   * Ends the boot where a workflow module wrote the key into its own section.
   * <p>
   * The core resolves an extension's setting per workflow module, so such a value binds and would
   * simply never be read: the memory it sizes is one per node and shared by every module. A value
   * which cannot do what it says is worth a message naming the key which can, the way VanillaBP's
   * name-clash avoidance names the levels it found a mode at.
   */
  private static void refuseWhatAWorkflowModuleConfigured(
      final MigrationAdapterProperties properties,
      final String key) {

    final var modules = properties
        .getWorkflowModules()
        .entrySet()
        .stream()
        .filter(
            module -> module
                .getValue()
                .getExtensions()
                .getOrDefault(ConfigurationKeys.EXTENSION_ID, Map.of())
                .get(REMEMBERED_USER_TASKS) != null)
        .map(
            module -> ConfigurationKeys
                .workflowModuleKey(module.getKey(), REMEMBERED_USER_TASKS))
        .toList();
    if (modules.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            The Business Cockpit's Process-Engine-API half was configured per workflow module (%s), \
            and it reads this setting globally only: it says how many delivered user tasks one node \
            remembers, and that memory is one per node and shared by every workflow module. Move the \
            value to '%s' or remove it, which leaves it at %d."""
            .formatted(
                String.join(", ", modules), key, DEFAULT_REMEMBERED_USER_TASKS));

  }

}
