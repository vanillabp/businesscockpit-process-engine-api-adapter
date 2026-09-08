package io.vanillabp.cockpit.pea.test;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.handler.HandlerCall;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;

/**
 * VanillaBP's registry as far as this module's tests need it: the names a modeller wrote on the
 * BPMN elements, which is what an adapter hands to the wiring validation and what an extension
 * reads back instead of parsing the same bytes.
 * <p>
 * Everything else answers nothing. A details provider is invoked in the integration tests of the
 * two platform modules, where a real application brings a real registry.
 */
public class TestExtensionHandlers implements ExtensionHandlers {

  private final Map<String, String> bpmnTaskNames;

  /**
   * @param bpmnTaskNames The names of the BPMN elements, by activity id - what the adapter read
   *          out of the model it deployed
   */
  public TestExtensionHandlers(
      final Map<String, String> bpmnTaskNames) {

    this.bpmnTaskNames = Map.copyOf(bpmnTaskNames);

  }

  /**
   * @return A registry which knows no name at all, the way the Process-Engine-API adapter leaves
   *         it today
   */
  public static ExtensionHandlers withoutAnyBpmnName() {

    return new TestExtensionHandlers(Map.of());

  }

  @Override
  public void register(
      final HandlerContract contract) {

  }

  @Override
  public boolean hasHandler(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> lookupKeys) {

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

    return Optional.ofNullable(bpmnTaskNames.get(activityId));

  }

}
