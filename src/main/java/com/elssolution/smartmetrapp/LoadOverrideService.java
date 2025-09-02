package com.elssolution.smartmetrapp;

import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class LoadOverrideService {

    private volatile float overrideDeltaKw = 0.0f; // Додаткові кВт які треба "погасити"
    private final SolisCloudClientService solisCloudClientService;

    @Autowired
    public LoadOverrideService(SolisCloudClientService solisCloudClientService) {
        this.solisCloudClientService = solisCloudClientService;
    }


//     ❗ Увімкни або вимкни цей метод для ПРОДАКШЕНУ
    @PostConstruct
    public void setDeltaFromSolisServer() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                Optional<Double> currentGridPower = solisCloudClientService.getCurrentGridImportPower();
                currentGridPower.ifPresentOrElse(
                        power -> {
                            overrideDeltaKw = power > 1.0 ? (float)(power - 1.0) : 0f;
                            log.info("⚡ Data from Solis: " + power + " kW, Δ = " + overrideDeltaKw + " kW");
                        },
                        () -> System.out.println("⚠️ Дані з Solis тимчасово недоступні")
                );
            } catch (Exception e) {
                log.error("❌ Error when getting data from Solis: " + e.getMessage());
            }
        }, 5, 10, TimeUnit.SECONDS);
    }

//    @PostConstruct
//    public void setDeltaFromConsole() {
//        new Thread(() -> {
//            Scanner scanner = new Scanner(System.in);
//            while (true) {
//                System.out.print("Введіть споживання іншого інвертора в кВт: ");
//                String input = scanner.nextLine();
//                try {
//                    float otherLoadKw = Float.parseFloat(input);
//                    overrideDeltaKw = otherLoadKw > 1.0f ? otherLoadKw - 1.0f : 0f;
//                    System.out.println("✅ Буде додано " + overrideDeltaKw + " кВт до симуляції");
//                } catch (Exception e) {
//                    System.out.println("❌ Некоректне число");
//                }
//            }
//        }).start();
//    }

    public float getOverrideDeltaKw() {
        return overrideDeltaKw;
    }
}
