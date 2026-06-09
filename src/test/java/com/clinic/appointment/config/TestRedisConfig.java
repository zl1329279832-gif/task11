package com.clinic.appointment.config;

import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.service.RedisLockService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 测试环境配置：使用JVM本地锁替代Redisson分布式锁，无需Redis实例
 */
@Configuration
@Profile("test")
public class TestRedisConfig {

    @Bean
    @Primary
    public RedisLockService testRedisLockService() {
        return new RedisLockService() {
            private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

            @Override
            public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                          TimeUnit unit, Supplier<T> supplier) {
                ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
                boolean acquired = false;
                try {
                    acquired = lock.tryLock(waitTime, unit);
                    if (!acquired) {
                        throw BusinessException.lockAcquireFailed();
                    }
                    return supplier.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("LOCK_INTERRUPTED", "获取锁被中断", e);
                } finally {
                    if (acquired && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }

            @Override
            public <T> T executeWithLock(String lockKey, Supplier<T> supplier) {
                return executeWithLock(lockKey, 5, 10, TimeUnit.SECONDS, supplier);
            }
        };
    }
}
