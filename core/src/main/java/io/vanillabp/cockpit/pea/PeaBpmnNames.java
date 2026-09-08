package io.vanillabp.cockpit.pea;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The names a modeller wrote into a BPMN file, read out of the file the adapter deployed.
 * <p>
 * The cockpit shows a name where an application wrote no title, and the Process-Engine-API can
 * be asked for neither: it has no repository API, so nothing about a deployed process can be
 * read back from an engine. What VanillaBP's deployment pipeline handed the adapter is therefore
 * the only source, and this is the one place in the extension which looks into the XML.
 * <p>
 * What is read is the file as the adapter read it, with the identifiers the modeller wrote: the
 * <code>use-prefix</code> rewrite produces a second copy of the resource, which goes to the
 * engine and not through the deployment pipeline. So a process and its user tasks are found under
 * the ids the application wrote, whichever mode a workflow module is deployed with.
 */
public final class PeaBpmnNames {

  private static final Logger logger = LoggerFactory.getLogger(PeaBpmnNames.class);

  /**
   * What one BPMN file says about the process the extension asked for.
   *
   * @param processName The BPMN name of the process, or <code>null</code> where it has none
   * @param userTaskNames The BPMN names of the user tasks, by element id
   */
  public record Names(
                      String processName,
                      Map<String, String> userTaskNames) {

    static Names none() {

      return new Names(null, Map.of());

    }

  }

  private PeaBpmnNames() {
  }

  /**
   * Reads the names of one BPMN file.
   * <p>
   * A file which cannot be parsed costs the titles of its tasks and nothing else, so it is
   * reported and answered with no names: the workflows of that module run, and a cockpit
   * without a fallback title is better than an application which does not start.
   *
   * @param resource The BPMN XML as the adapter read it
   * @param bpmnProcessId The plain BPMN process id the caller is asking about
   * @return What the file says
   */
  public static Names read(
      final byte[] resource,
      final String bpmnProcessId) {

    if ((resource == null) || (resource.length == 0)) {
      return Names.none();
    }
    try (var input = new ByteArrayInputStream(resource)) {
      final var reader = inputFactory().createXMLStreamReader(input);
      try {
        return read(reader, bpmnProcessId);
      } finally {
        reader.close();
      }
    } catch (final Exception e) {
      logger
          .warn(
              "Process-Engine-API: the BPMN of process '{}' could not be read for the names of its process and its user tasks. The Business Cockpit reports it without them.",
              bpmnProcessId,
              e);
      return Names.none();
    }

  }

  private static Names read(
      final XMLStreamReader reader,
      final String bpmnProcessId) throws Exception {

    final var userTaskNames = new LinkedHashMap<String, String>();
    final var processNames = new LinkedHashMap<String, String>();
    while (reader.hasNext()) {
      if (reader.next() != XMLStreamConstants.START_ELEMENT) {
        continue;
      }
      final var element = reader.getLocalName();
      final var id = reader.getAttributeValue(null, "id");
      final var name = reader.getAttributeValue(null, "name");
      if ("process".equals(element) && (id != null)) {
        processNames.put(id, name);
      } else if ("userTask".equals(element) && (id != null) && (name != null)) {
        userTaskNames.put(id, name);
      }
    }
    return new Names(processNames.get(bpmnProcessId), Map.copyOf(userTaskNames));

  }

  private static XMLInputFactory inputFactory() {

    final var factory = XMLInputFactory.newInstance();
    // the file is the application's own BPMN, and neither a doctype nor an external entity is
    // ever part of one
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    return factory;

  }

}
