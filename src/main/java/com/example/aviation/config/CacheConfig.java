package com.example.aviation.config;

import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine local cache configuration.
 * Airport data rarely changes, so caching significantly reduces upstream API calls.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private final AviationApiProperties properties;

    public CacheConfig(AviationApiProperties properties) {
        this.properties = properties;
    }

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("airports");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(properties.getCacheExpireAfterWriteMinutes(), TimeUnit.MINUTES)
                .maximumSize(properties.getCacheMaximumSize())
                .recordStats());
        return cacheManager;
    }
}
