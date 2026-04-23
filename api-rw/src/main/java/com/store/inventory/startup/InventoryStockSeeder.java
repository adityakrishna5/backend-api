package com.store.inventory.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Seeds Redis with current stock levels from PostgreSQL on startup.
 *
 * Strategy: set from DB only if the Redis key is absent OR its value is <= 0.
 * This handles both cold startups (missing key → immediate -1 on DECR) and
 * previously-drained keys (value=0 despite DB having stock). Keys that are
 * actively positive are left untouched — safe for rolling restarts under load.
 *
 * Lua guarantees atomicity of the check-and-set pair.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryStockSeeder {

    private static final String STOCK_KEY_PREFIX = "inventory:stock:";
    private static final String SELECT_STOCK =
            "SELECT product_id, stock_level FROM inventory WHERE stock_level > 0";

    /**
     * SET key dbValue only when: key does not exist OR current value <= 0.
     * Returns 1 if the key was written, 0 if it was left as-is.
     */
    private static final DefaultRedisScript<Long> SEED_SCRIPT = new DefaultRedisScript<>(
            "local cur = tonumber(redis.call('GET', KEYS[1])) " +
            "if cur == nil or cur <= 0 then " +
            "  redis.call('SET', KEYS[1], ARGV[1]) return 1 " +
            "end return 0",
            Long.class
    );

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void seedRedisStockKeys() {
        log.info("InventoryStockSeeder: seeding Redis hot-path stock keys from DB...");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_STOCK);
        int seeded = 0;

        for (Map<String, Object> row : rows) {
            Long productId  = ((Number) row.get("product_id")).longValue();
            Long stockLevel = ((Number) row.get("stock_level")).longValue();
            String key      = STOCK_KEY_PREFIX + productId;

            Long written = stringRedisTemplate.execute(
                    SEED_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(stockLevel)
            );
            if (Long.valueOf(1L).equals(written)) {
                seeded++;
            }
        }

        log.info("InventoryStockSeeder: seeded {}/{} product stock keys into Redis", seeded, rows.size());
    }
}
