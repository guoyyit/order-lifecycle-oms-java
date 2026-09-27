package com.github.guoyyit.orderlifecycleomsjava.config;

import com.github.guoyyit.orderlifecycleomsjava.store.OrderStore;
import com.github.guoyyit.orderlifecycleomsjava.store.QuarantineStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存储装配。
 */
@Configuration
public class StoreConfig {

    @Bean
    public OrderStore orderStore() {
        return new OrderStore();
    }

    @Bean
    public QuarantineStore quarantineStore() {
        return new QuarantineStore();
    }
}
