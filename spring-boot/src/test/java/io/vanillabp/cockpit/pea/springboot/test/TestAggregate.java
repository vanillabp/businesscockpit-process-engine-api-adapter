package io.vanillabp.cockpit.pea.springboot.test;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * The business case of the test: what the workflow is about and what a details provider
 * changes.
 */
@Entity
public class TestAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String customer;

  private String note;

  public Long getId() {

    return id;

  }

  public void setId(
      final Long id) {

    this.id = id;

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
