package io.vanillabp.cockpit.pea.springboot.test;

import java.util.List;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.spi.cockpit.workflowmodules.WorkflowModuleDetailsProvider;

/**
 * The application in which the Process-Engine-API half of the Business Cockpit extension is
 * tested. It has a JPA workflow aggregate, a workflow service with details providers, and the
 * in-memory engine the VanillaBP Process-Engine-API adapter uses when an application brings no
 * engine of its own.
 */
@SpringBootApplication
public class TestApplication {

  /** The workflow module of the test. */
  public static final String MODULE_ID = "pea-cockpit";

  /** The groups this application reports as allowed to see its cases. */
  public static final List<String> ACCESSIBLE_TO_GROUPS = List.of("drivers", "dispatch");

  /**
   * @return What the cockpit server is told about this workflow module
   */
  @Bean
  public WorkflowModuleDetailsProvider workflowModuleDetailsProvider() {

    return new WorkflowModuleDetailsProvider() {

      @Override
      public List<String> getAccessibleToGroups() {

        return ACCESSIBLE_TO_GROUPS;

      }

      @Override
      public String getWorkflowModuleId() {

        return MODULE_ID;

      }

    };

  }

}
