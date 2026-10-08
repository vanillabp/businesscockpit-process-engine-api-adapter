package io.vanillabp.cockpit.pea.quarkus.it;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What happens to a user task which has no <code>&#64;WorkflowTask</code> method and is not
 * marked as served elsewhere, inside a Quarkus application.
 * <p>
 * It is the twin of the Spring Boot test of the same name, which says why a details provider of
 * the Business Cockpit does not count as serving a task. The line which marks the task is taken
 * away here, so the start has to end and name the line to add.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class UnmarkedUserTaskTest {

  private static final String MARKER_OF_THE_UNSERVED_TASK = "vanillabp.workflow-modules.pea-cockpit.workflows.%s.tasks.%s.implemented-externally"
      .formatted(TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.UNSERVED_BPMN_TASK_ID);

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = TestApplication.forTestClass(UnmarkedUserTaskTest.class)
      .overrideRuntimeConfigKey(MARKER_OF_THE_UNSERVED_TASK, "false")
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
                        "Task wiring of BPMN process '%s' of workflow module 'pea-cockpit' is incomplete"
                            .formatted(TestWorkflowService.BPMN_PROCESS_ID)),
                "the start ends over the task nothing serves: "
                    + messages);
        Assertions
            .assertTrue(
                messages
                    .toString()
                    .contains("%s=true".formatted(MARKER_OF_THE_UNSERVED_TASK)),
                "the message names the line which marks the task: "
                    + messages);
      });

  @Test
  @DisplayName("A user task with only a details provider and no marker ends the start")
  public void anUnmarkedUserTaskEndsTheStart() {
    // the expected startup failure is asserted above, so this never runs
  }

}
