package com.store.inventory.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "inventory")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    @Column(name = "stock_level", nullable = false)
    private Integer stockLevel;

    @Column(name = "reserved_qty", nullable = false)
    private Integer reservedQty;

    @Version
    private Long version;   // optimistic lock — second line of defence after Redisson RLock
}
