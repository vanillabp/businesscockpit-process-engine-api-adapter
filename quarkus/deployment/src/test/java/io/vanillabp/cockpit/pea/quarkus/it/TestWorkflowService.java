package io.vanillabp.cockpit.pea.quarkus.it;

import java.util.List;
import java.util.Map;

import io.vanillabp.spi.cockpit.details.DetailsEvent;
import io.vanillabp.spi.cockpit.usertask.PrefilledUserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetailsProvider;
import io.vanillabp.spi.cockpit.workflow.PrefilledWorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetailsProvider;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The application under test: a taxi ride whose user task the cockpit is to show, the Quarkus
 * twin of the Spring Boot module's test application.
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

  /** What the details provider writes into the aggregate, so that a test can see it ran. */
  public static final String APPROVE_NOTE = "seen by the details provider";

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
