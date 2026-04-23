package com.store.inventory.repository;

import com.store.common.dto.InventoryUpdateRequest;
import com.store.inventory.model.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * Pessimistic write lock — issues SELECT ... FOR UPDATE.
     * Combined with Redisson RLock in InventoryService to prevent oversell.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.productId = :productId")
    Optional<Inventory> findByProductIdForUpdate(@Param("productId") Long productId);

    @Modifying
    @Query("UPDATE Inventory i SET i.stockLevel = i.stockLevel - :qty, "
         + "i.reservedQty = i.reservedQty + :qty "
         + "WHERE i.productId = :productId AND i.stockLevel >= :qty")
    int decrementStock(@Param("productId") Long productId, @Param("qty") int qty);

    default void reserveStock(Long productId, Object request) {
        int qty = 1;
        if (request instanceof InventoryUpdateRequest r) {
            qty = r.quantity() != null ? r.quantity() : 1;
        }
        int updated = decrementStock(productId, qty);
        if (updated == 0) {
            throw new IllegalStateException("Insufficient stock for product " + productId);
        }
    }
}
