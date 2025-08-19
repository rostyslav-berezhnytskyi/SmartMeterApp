package com.elssolution.smartmetrapp;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class LoadOverrideService {

    private volatile float overrideDeltaKw = 0.0f; // Додаткові кВт які треба "погасити"
    private final SolisCloudClientService solisCloudClientService;

    @Autowired
    public LoadOverrideService(SolisCloudClientService solisCloudClientService) {
        this.solisCloudClientService = solisCloudClientService;
    }


    // ❗ Увімкни або вимкни цей метод для ПРОДАКШЕНУ
    @PostConstruct
    public void setDeltaFromSolisServer() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
                solisCloudClientService.testApiConnection();
        }, 5, 15, TimeUnit.SECONDS);
    }

    // ❗ Увімкни або вимкни цей метод для ПРОДАКШЕНУ
//    @PostConstruct
//    public void setDeltaFromSolisServer() {
//        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
//            try {
//                Optional<Double> currentGridPower = solisCloudClientService.getCurrentGridPower();
//                currentGridPower.ifPresentOrElse(
//                        power -> {
//                            overrideDeltaKw = power > 1.0 ? (float)(power - 1.0) : 0f;
//                            System.out.println("⚡ Дані з Solis: " + power + " кВт, Δ = " + overrideDeltaKw + " кВт");
//                        },
//                        () -> System.out.println("⚠️ Дані з Solis тимчасово недоступні")
//                );
//            } catch (Exception e) {
//                System.out.println("❌ Помилка при отриманні даних з Solis: " + e.getMessage());
//            }
//        }, 5, 15, TimeUnit.SECONDS);
//    }

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
