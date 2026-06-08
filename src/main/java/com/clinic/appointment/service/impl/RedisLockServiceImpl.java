package com.clinic.appointment.service.impl;

import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.service.RedisLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLockServiceImpl implements RedisLockService {

    private final RedissonClient redissonClient;

    @Override
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                  TimeUnit unit, Supplier<T> supplier) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime, leaseTime, unit);
            if (!acquired) {
                log.warn("获取锁失败: key={}", lockKey);
                throw BusinessException.lockAcquireFailed();
            }
            log.debug("获取锁成功: key={}", lockKey);
            return supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("LOCK_INTERRUPTED", "获取锁被中断", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("释放锁: key={}", lockKey);
            }
        }
    }

    @Override
    public <T> T executeWithLock(String lockKey, Supplier<T> supplier) {
        return executeWithLock(lockKey, 5, 10, TimeUnit.SECONDS, supplier);
    }
}
