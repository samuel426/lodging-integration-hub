package io.github.samuel426.lodginghub.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_type")
public class RoomType {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "stay_id", nullable = false, updatable = false)
  private Stay stay;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(nullable = false)
  private int maxOccupancy;

  @Column(nullable = false, updatable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected RoomType() {}

  public RoomType(Stay stay, String name, int maxOccupancy, Instant now) {
    this.id = UUID.randomUUID();
    this.stay = stay;
    this.createdAt = now;
    this.name = name;
    this.maxOccupancy = maxOccupancy;
    this.updatedAt = now;
  }

  public void updateDetails(String name, int maxOccupancy, Instant now) {
    this.name = name;
    this.maxOccupancy = maxOccupancy;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public Stay getStay() {
    return stay;
  }

  public String getName() {
    return name;
  }

  public int getMaxOccupancy() {
    return maxOccupancy;
  }
}
