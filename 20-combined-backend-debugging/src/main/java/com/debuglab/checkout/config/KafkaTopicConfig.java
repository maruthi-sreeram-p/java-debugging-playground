package com.debuglab.checkout.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic ordersTopic(@Value("${checkout.topic.orders}") String ordersTopic) {
        return TopicBuilder.name(ordersTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
