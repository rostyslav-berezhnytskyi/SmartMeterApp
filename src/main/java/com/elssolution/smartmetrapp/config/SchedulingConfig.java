package com.elssolution.smartmetrapp.config;

import com.elssolution.smartmetrapp.alerts.GlobalUncaughtHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.*;

@Slf4j
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {
    private final GlobalUncaughtHandler handler;

    public SchedulingConfig(GlobalUncaughtHandler handler) {
        this.handler = handler;
    }

    @Bean(destroyMethod = "shutdown") // it’s just a cleanup hook. when the application context shuts down, call the shutdown() method on this bean. So no zombie threads
    public ScheduledExecutorService scheduler() {
        ScheduledThreadPoolExecutor ex =  new ScheduledThreadPoolExecutor(8, r -> {
            Thread t = new Thread(r);
            t.setName("sm-sched-" + t.getId()); // unique name → easier debugging/logging
            t.setDaemon(true); // don’t block JVM exit; Spring handles clean shutdown
            t.setUncaughtExceptionHandler(handler);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);         // keeps queue tidy if tasks cancel
        ex.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return ex;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(scheduler());
    }

}

