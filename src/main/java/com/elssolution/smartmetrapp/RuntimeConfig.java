package com.elssolution.smartmetrapp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

@Configuration
public class RuntimeConfig {
    @Bean(destroyMethod = "shutdown") // it’s just a cleanup hook. when the application context shuts down, call the shutdown() method on this bean. So no zombie threads
    public ScheduledExecutorService scheduler() {
        return new ScheduledThreadPoolExecutor(3, r -> {
            Thread t = new Thread(r);
            t.setName("sm-sched-" + t.getId()); // unique name → easier debugging/logging
            t.setDaemon(true); // don’t block JVM exit; Spring handles clean shutdown
            return t;
        });
    }
}

