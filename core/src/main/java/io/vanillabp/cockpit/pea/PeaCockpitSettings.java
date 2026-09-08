package io.vanillabp.cockpit.pea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.cockpit.extension.config.CockpitSettings;
import io.vanillabp.cockpit.extension.config.ConfigurationKeys;

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

  /**
   * How many delivered user tasks a node remembers at once, below
   * <code>vanillabp.cockpit</code>.
   * <p>
   * The key stands in the extension's own tree rather than in one of this half's, and the
   * extension declares it for both platforms: a key below <code>vanillabp</code> which no
   * binding declares ends the boot on Quarkus.
   */
  public static final String REMEMBERED_USER_TASKS = ConfigurationKeys.REMEMBERED_USER_TASKS;

  /**
   * Enough for the tasks of a busy node between a delivery and the dispatch which follows it
   * seconds later, and small enough to be paid for without thinking about it.
   */
  public static final int DEFAULT_REMEMBERED_USER_TASKS = 1000;

  private PeaCockpitSettings() {
  }

  /**
   * @param settings What the application wrote below the cockpit's own sections
   * @return How many delivered user tasks a node is to remember
   * @throws IllegalStateException If the configured value is not a number or not positive; the
   *           message names the key and the default
   */
  public static int rememberedUserTasks(
      final CockpitSettings settings) {

    final var key = ConfigurationKeys.globalKey(REMEMBERED_USER_TASKS);
    final var configured = settings.processEngineApi() == null
        ? null
        : settings.processEngineApi().rememberedUserTasks();
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

}
