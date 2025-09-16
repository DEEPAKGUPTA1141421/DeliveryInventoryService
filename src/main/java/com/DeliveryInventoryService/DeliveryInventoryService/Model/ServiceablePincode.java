package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import java.util.UUID;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "serviceable_pincodes")
@Data
public class ServiceablePincode {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String pincode;

    @Column(nullable = false)
    private String city;
}

// huyuyt6utydhuiy8y8y8i t6t7t7t

// kjiuhihyy8y7   gyutut6yg gyutyu7 uyyu