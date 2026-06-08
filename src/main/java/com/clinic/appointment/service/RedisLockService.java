package com.clinic.appointment.service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis分布式锁服务
 */
public interface RedisLockService {

    /**
     * 在锁保护下执行操作
     * @param lockKey    锁键
     * @param waitTime   等待获取锁的最大时间
     * @param leaseTime  锁持有时间
     * @param unit       时间单位
     * @param supplier   业务逻辑
     */
    <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                           TimeUnit unit, Supplier<T> supplier);

    /**
     * 简易版本：默认等待5s，持有10s
     */
    <T> T executeWithLock(String lockKey, Supplier<T> supplier);
}
