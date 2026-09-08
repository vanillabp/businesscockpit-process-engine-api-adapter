package io.vanillabp.cockpit.pea;

/**
 * What somebody observing the Process-Engine-API's user-task subscriptions hands the Business
 * Cockpit.
 * <p>
 * <b>This interface is a placeholder for a seam the VanillaBP Process-Engine-API adapter does not
 * have yet</b>, and this repository's <code>GAPS.md</code> says what that seam would
 * look like. The Process-Engine-API delivers one task to exactly one subscription - its reference
 * engine adapter picks the first subscription matching a task and remembers it as the one active
 * for that task - so an extension cannot subscribe next to the adapter and watch along: it would
 * either see nothing or take the delivery away from the workflow application. Composing another
 * handler into the adapter's own subscription is the only way, and only the adapter can do it.
 * <p>
 * Until it does, this is where the cockpit's half of that composition already sits: a bean of
 * this type is produced by both platform modules, an application observing tasks itself can call
 * it, and the tests of this repository drive it the way the adapter would. When the adapter grows
 * the seam, {@link PeaCockpitObserver} implements the adapter's interface instead of this one and
 * nothing else about the extension changes. Why the extension waits for that rather than
 * subscribing itself is decision 5 in the repository's DECISIONS.md.
 */
public interface PeaUserTaskObserver {

  /**
   * A user task was delivered: it exists, and the engine says whether this is the first time
   * ({@link dev.bpmcrafters.processengineapi.task.TaskInformation#CREATE}) or a repetition
   * because something about it changed (<code>assign</code>, <code>update</code>).
   *
   * @param observation What the engine delivered
   */
  void userTaskDelivered(
      PeaUserTaskObservation observation);

  /**
   * A user task the engine had delivered is gone. Whether somebody finished it or something
   * withdrew it is not part of the Process-Engine-API - see decision 4 in the repository's
   * DECISIONS.md.
   *
   * @param observation What the engine reported about the terminated task
   */
  void userTaskTerminated(
      PeaUserTaskObservation observation);

}
