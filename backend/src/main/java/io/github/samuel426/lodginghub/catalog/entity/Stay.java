package io.github.samuel426.lodginghub.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stay")
public class Stay {
  @Id private UUID id;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected Stay() {}

  public Stay(String name, Instant now) {
    this.id = UUID.randomUUID();
    this.createdAt = now;
    this.name = name;
    this.updatedAt = now;
  }

  public void rename(String name, Instant now) {
    this.name = name;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
