package io.vanillabp.cockpit.pea;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.TaskInformation;

/**
 * What the Business Cockpit reads out of the meta map an engine delivers a user task with.
 * <p>
 * The Process-Engine-API defines a vocabulary for the keys a subscription is restricted by
 * ({@link CommonRestrictions}) and none for the keys a delivered task carries: an engine adapter
 * fills whatever it has. The keys spelled out here are the ones the API's own reference adapter
 * for an embedded Camunda 7 fills, so they are what a cockpit can expect where it can expect
 * anything - and a key an engine does not fill simply means the cockpit shows one detail less.
 * Never an error: a user task the engine reports is worth showing with an assignee missing.
 * <p>
 * That this vocabulary is a convention rather than a contract is written down in this
 * repository's <code>GAPS.md</code>, where it is the gap the Process-Engine-API could close
 * cheapest.
 */
public final class PeaTaskMeta {

  private static final Logger logger = LoggerFactory.getLogger(PeaTaskMeta.class);

  /** The BPMN element id of the user task, which the cockpit matches a details provider by. */
  public static final String BPMN_TASK_ID = CommonRestrictions.ACTIVITY_ID;

  /** The engine's own id of the workflow the task belongs to. */
  public static final String WORKFLOW_ID = CommonRestrictions.PROCESS_INSTANCE_ID;

  /** The version tag of the deployed process definition, where an engine keeps one. */
  public static final String PROCESS_VERSION_TAG = CommonRestrictions.PROCESS_DEFINITION_VERSION_TAG;

  /** The BPMN name of the user task, the cockpit's fallback title. */
  public static final String TASK_NAME = "taskName";

  /** Who the task is assigned to. */
  public static final String ASSIGNEE = "assignee";

  /** Who may claim the task, comma separated. */
  public static final String CANDIDATE_USERS = "candidateUsers";

  /** Whose members may claim the task, comma separated. */
  public static final String CANDIDATE_GROUPS = "candidateGroups";

  /** When the task is due, ISO-8601. */
  public static final String DUE_DATE = "dueDate";

  /** When somebody wants to be reminded of the task, ISO-8601. */
  public static final String FOLLOW_UP_DATE = "followUpDate";

  private PeaTaskMeta() {
  }

  /**
   * @param taskInformation What the engine delivered
   * @param key One of the keys above
   * @return The value, or <code>null</code> where the engine filled none
   */
  public static String text(
      final TaskInformation taskInformation,
      final String key) {

    final var value = meta(taskInformation).get(key);
    return (value == null) || value.isBlank()
        ? null
        : value;

  }

  /**
   * @param taskInformation What the engine delivered
   * @param key One of the keys above
   * @return The comma-separated value as a list, empty where the engine filled none
   */
  public static List<String> list(
      final TaskInformation taskInformation,
      final String key) {

    final var value = text(taskInformation, key);
    if (value == null) {
      return List.of();
    }
    return Arrays
        .stream(value.split(","))
        .map(String::trim)
        .filter(entry -> !entry.isEmpty())
        .toList();

  }

  /**
   * A date the engine wrote into its meta map.
   * <p>
   * A value which is not a date is reported and dropped rather than thrown: the task itself is
   * worth showing, and the defect belongs to the engine which wrote the value.
   *
   * @param taskInformation What the engine delivered
   * @param key One of the keys above
   * @return The date, or <code>null</code> where the engine filled none or wrote nonsense
   */
  public static OffsetDateTime timestamp(
      final TaskInformation taskInformation,
      final String key) {

    final var value = text(taskInformation, key);
    if (value == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (final DateTimeParseException e) {
      logger
          .warn(
              "Process-Engine-API: user task '{}' carries '{}' as its '{}', which is not an ISO-8601 timestamp. The task is reported to the Business Cockpit without it.",
              taskInformation.getTaskId(),
              value,
              key);
      return null;
    }

  }

  private static Map<String, String> meta(
      final TaskInformation taskInformation) {

    return taskInformation.getMeta() == null
        ? Map.of()
        : taskInformation.getMeta();

  }

}
