package io.vanillabp.cockpit.pea.springboot.test;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

/**
 * The business case of the test. It says what the workflow is about and what a details provider
 * changes.
 * <p>
 * It carries a version attribute, and that is what an application with a writing details provider
 * is meant to do. The provider runs while the report is built, in the transaction the cockpit
 * opens for the outbox entry, and it writes this case back when that transaction commits. An
 * application which changes the same case in that window would otherwise lose its change without
 * a word, because the provider writes every field it holds, the older reading among them. With
 * the version attribute the later of the two writers reads a conflict instead, and each of them
 * answers it the way it can. The application repeats its transaction. The cockpit loses the
 * entry, so the report of that event is gone and the next one carries the case as it is then.
 */
@Entity
public class TestAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** What the persistence increments per write, and what a second writer runs into. */
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
