package io.vanillabp.cockpit.pea.springboot.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What happens to a user task which has no <code>&#64;WorkflowTask</code> method and is not
 * marked as served elsewhere.
 * <p>
 * The VanillaBP core ends the start for such a task. A details provider of the Business Cockpit
 * does not count as serving it: the provider says what the cockpit shows, and nothing in it works
 * the task off. So the application has to say that a task list does, with the line
 * <code>implemented-externally=true</code>. {@link UnservedUserTaskTest} boots the same model
 * with that line and shows what the cockpit hears. This test takes the line away for one task and
 * shows that the start ends, naming the line to add.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class UnmarkedUserTaskTest {

  private static final String MARKER_OF_THE_UNSERVED_TASK = "vanillabp.workflow-modules.pea-cockpit.workflows.%s.tasks.%s.implemented-externally"
      .formatted(TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.UNSERVED_BPMN_TASK_ID);

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
  @DisplayName("A user task with only a details provider and no marker ends the start")
  public void anUnmarkedUserTaskEndsTheStart() {

    final var refused = assertThrows(
        Exception.class,
        () -> new SpringApplicationBuilder(TestApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=jdbc:h2:mem:pea-cockpit-unmarked-user-task",
                "--vanillabp.cockpit.rest.base-url=%s".formatted(CockpitServer.baseUrl()),
                "--%s=false".formatted(MARKER_OF_THE_UNSERVED_TASK))
            .close());

    final var messages = messagesOf(refused);
    assertTrue(
        messages
            .contains(
                "Task wiring of BPMN process '%s' of workflow module 'pea-cockpit' is incomplete"
                    .formatted(TestWorkflowService.BPMN_PROCESS_ID)),
        () -> "the start ends over the task nothing serves: "
            + messages);
    assertTrue(
        messages.contains("%s=true".formatted(MARKER_OF_THE_UNSERVED_TASK)),
        () -> "the message names the line which marks the task: "
            + messages);

  }

}
