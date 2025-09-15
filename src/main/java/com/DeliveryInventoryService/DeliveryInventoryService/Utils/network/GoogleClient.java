package com.DeliveryInventoryService.DeliveryInventoryService.Utils.network;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name = "googleDistanceMatrix", url = "https://maps.googleapis.com/maps/api/distancematrix")
public interface GoogleClient {

        @GetMapping("/json")
        Map<String, Object> getDistanceMatrix(
                        @RequestParam("origins") String origins,
                        @RequestParam("destinations") String destinations,
                        @RequestParam("key") String apiKey);
}
