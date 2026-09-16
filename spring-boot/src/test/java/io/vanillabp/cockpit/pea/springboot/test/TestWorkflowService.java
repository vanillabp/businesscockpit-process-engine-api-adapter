package io.vanillabp.cockpit.pea.springboot.test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.cockpit.BusinessCockpitService;
import io.vanillabp.spi.cockpit.details.DetailsEvent;
import io.vanillabp.spi.cockpit.usertask.PrefilledUserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetailsProvider;
import io.vanillabp.spi.cockpit.workflow.PrefilledWorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetailsProvider;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The application under test. It is a taxi ride whose user task the cockpit is to show, with a
 * details provider which enriches what the engine delivered and writes into the aggregate while
 * doing so.
 */
@Service
@WorkflowService(
    workflowAggregateClass = TestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = TestWorkflowService.BPMN_PROCESS_ID))
public class TestWorkflowService {

  /** The BPMN process of the test. */
  public static final String BPMN_PROCESS_ID = "TaxiRide";

  /** The external form reference of the user task, which is what the cockpit calls its task definition. */
  public static final String TASK_DEFINITION = "approve-the-ride";

  /** The BPMN element id of the user task. */
  public static final String BPMN_TASK_ID = "Approve";

  /**
   * The external form reference of the second user task. No <code>&#64;WorkflowTask</code> method
   * of this application claims it, which is what the measurement of story 1299 is about: a task
   * worked on in a task list only.
   */
  public static final String UNSERVED_TASK_DEFINITION = "inspect-the-car";

  /** The BPMN element id of the user task nothing claims. */
  public static final String UNSERVED_BPMN_TASK_ID = "Inspect";

  /** The task ids the <code>&#64;WorkflowTask</code> method of this service was called for. */
  public static final List<String> SERVED_NOTIFICATIONS = new CopyOnWriteArrayList<>();

  /** What the details provider writes into the aggregate, so that a test can see it ran. */
  public static final String APPROVE_NOTE = "seen by the details provider";

  private final ProcessService<TestAggregate> processService;

  private final BusinessCockpitService<TestAggregate> businessCockpitService;

  public TestWorkflowService(
      final ProcessService<TestAggregate> processService,
      final BusinessCockpitService<TestAggregate> businessCockpitService) {

    this.processService = processService;
    this.businessCockpitService = businessCockpitService;

  }

  /**
   * @return The process service, so that a test can start a workflow
   */
  public ProcessService<TestAggregate> processes() {

    return processService;

  }

  /**
   * @return The cockpit service, so that a test can report a change of its own
   */
  public BusinessCockpitService<TestAggregate> businessCockpit() {

    return businessCockpitService;

  }

  /**
   * Matched by the external form reference of the user task. It enriches what the engine
   * delivered and writes into the workflow aggregate, which is what a details provider is for.
   * <p>
   * The write has to stay even though no test reads the note any more. It is what leaves the case
   * dirty in the transaction which dispatches the report. A dispatch writing nothing is no second
   * writer of the case, and a second writer is what the version attribute of TestAggregate
   * answers.
   *
   * @param aggregate The workflow aggregate, loaded by VanillaBP
   * @param prefilled What the engine knew about the task
   * @param event What happened to the task
   * @param passenger A variable of the delivered payload
   * @return The very object it was given, which is the common case
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION)
  public UserTaskDetails approve(
      final TestAggregate aggregate,
      final PrefilledUserTaskDetails prefilled,
      @DetailsEvent final DetailsEvent.Event event,
      @TaskParam("passenger") final String passenger) {

    aggregate.setNote(APPROVE_NOTE);
    prefilled
        .setDetails(
            Map
                .of(
                    "customer", aggregate.getCustomer(), "event", event.name(), "passenger", String
                        .valueOf(passenger)));
    prefilled.setCandidateGroups(List.of("drivers"));
    return prefilled;

  }

  /**
   * What the application is told about the FIRST user task. It is the control of the measurement:
   * a task this application has code for, next to one it has none for.
   * <p>
   * It changes nothing about the workflow aggregate on purpose. A handler which writes the case
   * would be a second writer next to the details provider of the report being dispatched, and
   * this test is about who hears of a task, not about who writes the case.
   *
   * @param aggregate The workflow aggregate, loaded by VanillaBP
   * @param taskId The engine's id of the task
   */
  @WorkflowTask(taskDefinition = TASK_DEFINITION)
  public void theApplicationIsNotifiedAboutTheApproval(
      final TestAggregate aggregate,
      @TaskId final String taskId) {

    SERVED_NOTIFICATIONS.add(taskId);

  }

  /**
   * The details of the user task nothing claims. It is wired by the external form reference like
   * every other provider, so the only thing telling the two user tasks apart is the missing
   * <code>&#64;WorkflowTask</code> method.
   *
   * @param aggregate The workflow aggregate, loaded by VanillaBP
   * @param prefilled What the engine knew about the task
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = UNSERVED_TASK_DEFINITION)
  public UserTaskDetails inspect(
      final TestAggregate aggregate,
      final PrefilledUserTaskDetails prefilled) {

    prefilled.setDetails(Map.of("customer", aggregate.getCustomer()));
    prefilled.setCandidateGroups(List.of("inspectors"));
    return prefilled;

  }

  /**
   * The one provider a BPMN process may have for its workflow.
   *
   * @param aggregate The workflow aggregate
   * @param prefilled What the engine knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider
  public WorkflowDetails workflowDetails(
      final TestAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    prefilled.setDetails(Map.of("customer", aggregate.getCustomer()));
    return prefilled;

  }

}
