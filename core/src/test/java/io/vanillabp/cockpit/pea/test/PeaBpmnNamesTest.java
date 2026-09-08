package io.vanillabp.cockpit.pea.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.cockpit.pea.PeaBpmnNames;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The only place the extension looks into a BPMN file, and the file it looks into is whatever a
 * modeller drew. One file may hold several processes, and two of them may call the same element
 * id something different, so what is read has to belong to the process which was asked about.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaBpmnNamesTest {

  /**
   * Two processes talking to each other, as a modeller draws a collaboration: both have a user
   * task under the element id <code>approve</code>, and each calls it something else.
   */
  private static final String COLLABORATION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1">
        <bpmn:collaboration id="Collaboration_1">
          <bpmn:participant id="rider" processRef="ARide" />
          <bpmn:participant id="driver" processRef="ADriverShift" />
        </bpmn:collaboration>
        <bpmn:process id="ARide" name="A taxi ride" isExecutable="true">
          <bpmn:userTask id="approve" name="Approve the ride" />
        </bpmn:process>
        <bpmn:process id="ADriverShift" name="A driver's shift" isExecutable="true">
          <bpmn:userTask id="approve" name="Approve the shift" />
          <bpmn:userTask id="hand-over" name="Hand the car over" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static PeaBpmnNames.Names namesOf(
      final String bpmnProcessId) {

    return PeaBpmnNames.read(COLLABORATION.getBytes(StandardCharsets.UTF_8), bpmnProcessId);

  }

  @Test
  @DisplayName("Each process of a collaboration is read with its own names")
  public void eachProcessOfACollaborationIsReadWithItsOwnNames() {

    final var ride = namesOf("ARide");
    assertEquals("A taxi ride", ride.processName());
    assertEquals("Approve the ride", ride.userTaskNames().get("approve"));
    assertNull(
        ride.userTaskNames().get("hand-over"),
        "a task of the other process is none of this one's");

    final var shift = namesOf("ADriverShift");
    assertEquals("A driver's shift", shift.processName());
    assertEquals("Approve the shift", shift.userTaskNames().get("approve"));
    assertEquals("Hand the car over", shift.userTaskNames().get("hand-over"));

  }

  @Test
  @DisplayName("A process the file does not hold is answered with no names at all")
  public void aProcessWhichIsNotInTheFileHasNoNames() {

    final var names = namesOf("AnotherProcess");

    assertNull(names.processName());
    assertTrue(names.userTaskNames().isEmpty());

  }

  @Test
  @DisplayName("A file which cannot be read costs the titles and not the workflow")
  public void aFileWhichCannotBeReadCostsTheTitlesOnly() {

    final var names = PeaBpmnNames
        .read("this is not a BPMN file".getBytes(StandardCharsets.UTF_8), "ARide");

    assertNull(names.processName());
    assertTrue(names.userTaskNames().isEmpty());

  }

}
