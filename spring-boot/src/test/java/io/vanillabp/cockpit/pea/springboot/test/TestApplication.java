package io.vanillabp.cockpit.pea.springboot.test;

import java.util.List;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.spi.cockpit.workflowmodules.WorkflowModuleDetailsProvider;

/**
 * The application the Process-Engine-API half of the Business Cockpit extension is tested
 * inside: a JPA workflow aggregate, a workflow service with details providers, and the
 * in-memory engine the VanillaBP Process-Engine-API adapter runs against when an application
 * brings none of its own.
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
