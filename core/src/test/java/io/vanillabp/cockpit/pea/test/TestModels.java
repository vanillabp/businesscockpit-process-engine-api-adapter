package io.vanillabp.cockpit.pea.test;

import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.vanillabp.cockpit.pea.PeaBusinessCases;
import io.vanillabp.cockpit.pea.PeaRecordedUserTasks;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.handler.HandlerCall;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;

/**
 * The workflow module the tests of this module deploy. It is one BPMN process with one user task,
 * recorded the way the Process-Engine-API adapter records it while deploying.
 */
public final class TestModels {

  public static final String ADAPTER_ID = "pea";

  public static final String MODULE_ID = "a-module";

  public static final String BPMN_PROCESS_ID = "ARide";

  public static final String PROCESS_NAME = "A taxi ride";

  public static final String USER_TASK_ELEMENT = "approve";

  public static final String USER_TASK_FORM = "approve-the-ride";

  public static final String USER_TASK_NAME = "Approve the ride";

  /** What the engine answered when the adapter deployed the file, the only version there is. */
  public static final String DEPLOYMENT_KEY = "deployment-7";

  /**
   * The version tag an engine writes into the meta map of a delivered task, where it keeps one.
   * It is the only thing this BPMS ever reports as the version of a process, and it is what a
   * details provider naming a version has to be written against here.
   */
  public static final String VERSION_TAG = "ride-2026-09";

  /**
   * The BPMN file as a modeller would leave it. The adapter deploys these bytes and reads the two
   * names out of them on the way, so what the model carries below is what the cockpit shows and
   * nothing here opens the file again.
   */
  public static final String BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1">
        <bpmn:process id="ARide" name="A taxi ride" isExecutable="true">
          <bpmn:startEvent id="start" />
          <bpmn:userTask id="approve" name="Approve the ride" />
          <bpmn:endEvent id="end" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  /** The class of the workflow aggregate the test's BPMN process is served by. */
  public static final class TestAggregate {
  }

  private TestModels() {
  }

  /**
   * The reader of VanillaBP's delivery log, wired the way a platform wires it.
   *
   * @param deployedProcesses What the adapter recorded while it deployed
   * @param deliveryLog The store the application runs, or <code>null</code> for an application
   *          which configured none
   * @return The reader
   */
  public static PeaRecordedUserTasks recorded(
      final PeaDeployedProcessesRegistry deployedProcesses,
      final TaskDeliveryLog deliveryLog) {

    return new PeaRecordedUserTasks(
        handlersServing(TestAggregate.class), resolvingTo(deliveryLog), deployedProcesses);

  }

  /**
   * @param deliveryLog What every aggregate's records are in, or <code>null</code> where there
   *          is no store at all
   * @return The platform's resolver
   */
  public static TaskDeliveryLogResolver resolvingTo(
      final TaskDeliveryLog deliveryLog) {

    return new TaskDeliveryLogResolver() {

      @Override
      public TaskDeliveryLog resolveFor(
          final Class<?> workflowAggregateClass) {

        return deliveryLog;

      }

      @Override
      public String remediesDescription() {

        return "";

      }

    };

  }

  /**
   * VanillaBP's handlers, answering the one question this extension asks them.
   *
   * @param workflowAggregateClass The aggregate serving the test's BPMN process, or
   *          <code>null</code> for a process no workflow service of the application declares
   * @return The handlers
   */
  public static ExtensionHandlers handlersServing(
      final Class<?> workflowAggregateClass) {

    return new ExtensionHandlers() {

      @Override
      public void register(
          final HandlerContract contract) {

      }

      @Override
      public boolean hasHandler(
          final Class<? extends Annotation> annotationType,
          final String workflowModuleId,
          final String bpmnProcessId,
          final List<String> lookupKeys,
          final String processVersion) {

        return false;

      }

      @Override
      public Optional<Object> invoke(
          final HandlerCall call) {

        return Optional.empty();

      }

      @Override
      public Optional<Class<?>> workflowAggregateOf(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return Optional.ofNullable(workflowAggregateClass);

      }

      @Override
      public List<String> bpmnProcessesOf(
          final String workflowModuleId) {

        return List.of(BPMN_PROCESS_ID);

      }

      @Override
      public Optional<String> bpmnTaskNameOf(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String activityId) {

        return Optional.empty();

      }

    };

  }

  /**
   * Which case a delivered task belongs to, where no process shares the aggregate of another
   * one. Every task is then filed under the instance it sits in.
   *
   * @return The lookup
   */
  public static PeaBusinessCases noCallers() {

    return new PeaBusinessCases(claimingTheRide(), handlersServing(null), new TestElection());

  }

  /**
   * Which case a delivered task belongs to, where the ride calls a second process.
   *
   * @param calledBpmnProcessId The process the ride calls
   * @param sharingTheAggregate Whether that process works on the ride's aggregate
   * @param election What VanillaBP wrote down at the start of a ride
   * @return The lookup
   */
  public static PeaBusinessCases theRideCalls(
      final String calledBpmnProcessId,
      final boolean sharingTheAggregate,
      final WorkflowElection election) {

    final var claimed = claiming(BPMN_PROCESS_ID, calledBpmnProcessId);
    final var core = new WorkflowTaskWiring() {

      @Override
      public void validateTaskWiring(
          final String workflowModuleId,
          final String bpmnProcessId,
          final Collection<BpmnTaskSpec> tasks) {

      }

      @Override
      public void validateNoUnwiredWorkflowTaskMethods(
          final String workflowModuleId) {

      }

      @Override
      public String resolveWorkflowAggregateIdName(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return claimed.resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId);

      }

      @Override
      public boolean workflowsShareTheWorkflowAggregate(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String otherBpmnProcessId) {

        return bpmnProcessId.equals(otherBpmnProcessId) || sharingTheAggregate;

      }

    };
    final var handlers = handlersServing(Object.class);
    final var declared = new ExtensionHandlers() {

      @Override
      public void register(
          final HandlerContract contract) {

      }

      @Override
      public boolean hasHandler(
          final Class<? extends Annotation> annotationType,
          final String workflowModuleId,
          final String bpmnProcessId,
          final List<String> lookupKeys,
          final String processVersion) {

        return false;

      }

      @Override
      public Optional<Object> invoke(
          final HandlerCall call) {

        return Optional.empty();

      }

      @Override
      public Optional<Class<?>> workflowAggregateOf(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return handlers.workflowAggregateOf(workflowModuleId, bpmnProcessId);

      }

      @Override
      public List<String> bpmnProcessesOf(
          final String workflowModuleId) {

        // the primary process first, the way the core lists what an application declares
        return List.of(BPMN_PROCESS_ID, calledBpmnProcessId);

      }

      @Override
      public Optional<String> bpmnTaskNameOf(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String activityId) {

        return Optional.empty();

      }

    };
    return new PeaBusinessCases(core, declared, election);

  }

  /**
   * VanillaBP's answer to which BPMN processes a <code>&#64;WorkflowService</code> of the
   * application claims, where the test's own process is the only one.
   *
   * @return The answer
   */
  public static WorkflowTaskWiring claimingTheRide() {

    return claiming(BPMN_PROCESS_ID);

  }

  /**
   * VanillaBP's answer to which BPMN processes a <code>&#64;WorkflowService</code> of the
   * application claims. It answers the way the core does: a claimed process has the name of
   * its aggregate's id, and every other process has none.
   *
   * @param claimedBpmnProcessIds The processes a workflow service claims
   * @return The answer
   */
  public static WorkflowTaskWiring claiming(
      final String... claimedBpmnProcessIds) {

    final var claimed = Set.of(claimedBpmnProcessIds);
    return new WorkflowTaskWiring() {

      @Override
      public void validateTaskWiring(
          final String workflowModuleId,
          final String bpmnProcessId,
          final Collection<BpmnTaskSpec> tasks) {

      }

      @Override
      public void validateNoUnwiredWorkflowTaskMethods(
          final String workflowModuleId) {

      }

      @Override
      public String resolveWorkflowAggregateIdName(
          final String workflowModuleId,
          final String bpmnProcessId) {

        if (MODULE_ID.equals(workflowModuleId) && claimed.contains(bpmnProcessId)) {
          return "id";
        }
        throw new IllegalStateException("No @WorkflowService claims '%s'".formatted(bpmnProcessId));

      }

    };

  }

  /**
   * @return The model the adapter's deployment pipeline read
   */
  public static PeaBpmnModel model() {

    return model(USER_TASK_NAME);

  }

  /**
   * @param userTaskName The name the modeller wrote on the user task, or <code>null</code> for a
   *          task nobody named
   * @return The model the adapter's deployment pipeline read
   */
  public static PeaBpmnModel model(
      final String userTaskName) {

    return new PeaBpmnModel(
        "a-ride.bpmn", BPMN.getBytes(StandardCharsets.UTF_8), BPMN_PROCESS_ID, PROCESS_NAME, List
            .of(), List.of(BpmnTaskSpec.userTask(USER_TASK_ELEMENT, USER_TASK_FORM, userTaskName)));

  }

  /**
   * @return What the adapter recorded while it deployed the module, under the one adapter id
   *         these tests configure
   */
  public static PeaDeployedProcessesRegistry deployed() {

    return deployed(model());

  }

  /**
   * @param model The model the adapter deployed
   * @return What the adapter recorded while it deployed it
   */
  public static PeaDeployedProcessesRegistry deployed(
      final PeaBpmnModel model) {

    final var deployedProcesses = new PeaDeployedProcessesRegistry();
    deployedProcesses.forAdapter(ADAPTER_ID).record(MODULE_ID, model, DEPLOYMENT_KEY);
    return deployedProcesses;

  }

}
