package io.vanillabp.cockpit.pea.quarkus.test;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

import io.vanillabp.cockpit.pea.PeaRecordedUserTasks;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.handler.HandlerCall;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;

/**
 * A reader of VanillaBP's delivery log for tests which do not read the log. The tests of this
 * module ask which adapter ids get a bridge, and a bridge cannot be built without a reader. The
 * platform-neutral module tests what the reader answers, against a store which holds records.
 */
public final class TestRecordedUserTasks {

  private TestRecordedUserTasks() {
  }

  /**
   * @param deployedProcesses What the adapter recorded while it deployed
   * @return A reader of an application which configured no delivery log
   */
  public static PeaRecordedUserTasks knowingNothing(
      final PeaDeployedProcessesRegistry deployedProcesses) {

    return new PeaRecordedUserTasks(noHandlers(), noDeliveryLog(), deployedProcesses);

  }

  private static TaskDeliveryLogResolver noDeliveryLog() {

    return new TaskDeliveryLogResolver() {

      @Override
      public TaskDeliveryLog resolveFor(
          final Class<?> workflowAggregateClass) {

        return null;

      }

      @Override
      public String remediesDescription() {

        return "";

      }

    };

  }

  private static ExtensionHandlers noHandlers() {

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

        return Optional.empty();

      }

      @Override
      public List<String> bpmnProcessesOf(
          final String workflowModuleId) {

        return List.of();

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

}
