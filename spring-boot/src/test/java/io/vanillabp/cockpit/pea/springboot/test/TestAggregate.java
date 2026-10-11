package io.vanillabp.cockpit.pea.springboot.test;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

/**
 * The business case of the test. It says what the workflow is about and what a details provider
 * changes.
 * <p>
 * It has a version attribute, and an application whose details provider writes should have one
 * too. The provider runs while the report is built, in the transaction the cockpit opens for the
 * outbox entry, and it writes this case back when that transaction commits. If the application
 * changes the same case in that window, it would lose its change without a word, because the
 * provider writes every field it holds, including the old ones. With the version attribute, the
 * later of the two writers runs into a conflict instead, and each of them handles it as it can.
 * The application repeats its transaction. The cockpit loses the entry, so the report of that
 * event is gone and the next report carries the case as it is then.
 * <p>
 * Keeping the version out of the BPMS means that <code>allow-full-sync-with-bpms</code> has no
 * effect in this test application: the startup check returns as soon as an aggregate keeps
 * anything back. The line stays anyway. The Quarkus test application keeps nothing back and does
 * need it. Without the line here, this application would stop starting on the day somebody
 * removes the annotation.
 */
@Entity
public class TestAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * The persistence increments it with every write, and a second writer runs into it. It stays
   * in the application: no model of this workflow reads it, and a BPMN model could decide nothing
   * by this number.
   */
  @NoSyncWithBPMS
  @Version
  private Long version;

  private String customer;

  private String note;

  public Long getId() {

    return id;

  }

  public void setId(
      final Long id) {

    this.id = id;

  }

  public Long getVersion() {

    return version;

  }

  public void setVersion(
      final Long version) {

    this.version = version;

  }

  public String getCustomer() {

    return customer;

  }

  public void setCustomer(
      final String customer) {

    this.customer = customer;

  }

  public String getNote() {

    return note;

  }

  public void setNote(
      final String note) {

    this.note = note;

  }

}
