package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Request for full end-to-end ETA (seller → city route → user) */
@Data
public class FullEtaRequestDTO {

    // Seller location
    @NotNull
    private Double sellerLat;
    @NotNull
    private Double sellerLng;

    // City names for the inter-hub leg
    @NotBlank
    private String originCity;
    @NotBlank
    private String destCity;

    // User location
    @NotNull
    private Double userLat;
    @NotNull
    private Double userLng;
}