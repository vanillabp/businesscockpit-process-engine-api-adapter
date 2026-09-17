package io.vanillabp.cockpit.pea.quarkus.it;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The application under test. It is a taxi ride whose user task the cockpit is to show, and it is
 * the Quarkus twin of the Spring Boot module's test application.
 */
@ApplicationScoped
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
   * of this application claims it, which is what the measurement of story 1299 is about.
   */
  public static final String UNSERVED_TASK_DEFINITION = "inspect-the-car";

  /** The BPMN element id of the user task nothing claims. */
  public static final String UNSERVED_BPMN_TASK_ID = "Inspect";

  /**
   * The external form reference of the third user task. Its details provider names a version, so
   * it only runs for a delivery which says which version of the model it came from.
   */
  public static final String VERSIONED_TASK_DEFINITION = "pay-the-fare";

  /** The BPMN element id of the user task whose provider names a version. */
  public static final String VERSIONED_BPMN_TASK_ID = "Pay";

  /**
   * The version tag the engine of this test writes into the meta map of a delivered task. It is
   * the only thing the Process-Engine-API ever reports as the version of a process, and the
   * provider of the third user task is written against exactly this spelling.
   */
  public static final String VERSION_TAG = "ride-2026-09";

  /** What the provider of the third user task writes, so that a test can see whether it ran. */
  public static final String FARE_DETAIL = "priced by the provider";

  /** The task ids the <code>&#64;WorkflowTask</code> method of this service was called for. */
  public static final List<String> SERVED_NOTIFICATIONS = new CopyOnWriteArrayList<>();

  /** What the details provider writes into the aggregate, so that a test can see it ran. */
  public static final String APPROVE_NOTE = "seen by the details provider";

  /** What the details provider of the first user task throws while a test asks it to break. */
  public static final String PROVIDER_BROKE = "the details provider broke";

  /**
   * How many more calls of the details provider of the first user task are to fail. A test sets
   * it, the provider counts it down, and every other test meets a provider which works.
   */
  public static final AtomicInteger APPROVALS_TO_FAIL = new AtomicInteger();

  @Inject
  ProcessService<TestAggregate> processService;

  @Inject
  io.vanillabp.spi.cockpit.BusinessCockpitService<TestAggregate> businessCockpitService;

  /**
   * @return The process service, so that a test can start a workflow
   */
  public ProcessService<TestAggregate> processes() {

    return processService;

  }

  /**
   * @return The cockpit service, so that a test can report a change of its own
   */
  public io.vanillabp.spi.cockpit.BusinessCockpitService<TestAggregate> businessCockpit() {

    return businessCockpitService;

  }

  /**
   * Matched by the external form reference of the user task. It enriches what the engine
   * delivered and writes into the workflow aggregate, which is what a details provider is for.
   *
   * @param aggregate The workflow aggregate, loaded by VanillaBP
   * @param prefilled What the engine knew about the task
   * @param event What happened to the task
   * @param passenger A variable of the delivered payload
   * @return The very object it was given
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION)
  public UserTaskDetails approve(
      final TestAggregate aggregate,
      final PrefilledUserTaskDetails prefilled,
      @DetailsEvent final DetailsEvent.Event event,
      @TaskParam("passenger") final String passenger) {

    if (APPROVALS_TO_FAIL.getAndUpdate(left -> left > 0
        ? left - 1
        : 0) > 0) {
      throw new IllegalStateException(PROVIDER_BROKE);
    }
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
   * What the application is told about the FIRST user task. It is the control of the
   * measurement: a task this application has code for, next to one it has none for. It changes
   * nothing about the workflow aggregate on purpose.
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
   * The details of the user task the version decides about. It runs for the deliveries which say
   * they come from the version this method names, and for no other, which on this BPMS means the
   * deliveries carrying that version tag.
   * <p>
   * A delivery which names no version reaches no method at all, and that is allowed: the cockpit
   * then sends the prefilled details. Entry 12 in the repository's GAPS.md says what it costs.
   *
   * @param aggregate The workflow aggregate, loaded by VanillaBP
   * @param prefilled What the engine knew about the task
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = VERSIONED_TASK_DEFINITION, version = VERSION_TAG)
  public UserTaskDetails pay(
      final TestAggregate aggregate,
      final PrefilledUserTaskDetails prefilled) {

    prefilled.setDetails(Map.of("fare", FARE_DETAIL));
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
