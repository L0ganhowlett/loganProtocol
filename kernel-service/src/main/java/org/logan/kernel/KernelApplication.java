package org.logan.kernel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient; // ✅ modern replacement
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.listener.ChannelTopic;

@SpringBootApplication
@EnableDiscoveryClient // ✅ replaces @EnableEurekaClient
public class KernelApplication {

    @Value("${kernel.id}")
    private String kernelId;

    public static void main(String[] args) {
        SpringApplication.run(KernelApplication.class, args);
    }

    @Bean
    public ChannelTopic agentTopic() {
        // Each kernel has a dedicated Redis pub/sub channel
        return new ChannelTopic("kernel:" + kernelId);
    }
}
