package com.clinic.appointment;

import org.mockito.Mockito;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Test configuration that mocks Redis-related beans so tests can run
 * without a live Redis server.
 *
 * - RedissonClient: mocked to always grant distributed locks (lock.tryLock returns true).
 *   This lets concurrency control fall through to the CAS optimistic locking in the DB.
 * - RedisConnectionFactory: mocked to prevent Spring Data Redis auto-config from
 *   attempting a real connection. The existing RedisConfig.redisTemplate() will receive
 *   this mock factory and initialize successfully (afterPropertiesSet only checks non-null).
 */
@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public RedissonClient redissonClient() {
        RedissonClient client = Mockito.mock(RedissonClient.class);
        RLock lock = Mockito.mock(RLock.class);
        try {
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            when(lock.isHeldByCurrentThread()).thenReturn(true);
        } catch (InterruptedException e) {
            // won't happen in mock setup
        }
        when(client.getLock(anyString())).thenReturn(lock);
        return client;
    }

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return Mockito.mock(RedisConnectionFactory.class);
    }
}
