package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider.RiderStep;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

public record RiderSignupDTO(

        UUID id, // optional, useful for updates

        @Pattern(regexp = "^[0-9]{10}$", message = "Phone must be a 10-digit number") String phone,

        @Size(min = 2, max = 50, message = "Name must be between 2 and 50 characters") String name,

        @Pattern(regexp = "^[A-Z0-9-]{5,20}$", message = "License number must be alphanumeric (5–20 characters)") String licenseNumber,

        @Pattern(regexp = "^[A-Z0-9-]{5,20}$", message = "Vehicle number must be alphanumeric (5–20 characters)") String vehicleNumber,

        @NotNull(message = "Step is required") RiderStep step, List<MultipartFile> files, String vehicleTypeStr) {

    public boolean isValid() {
        if (step == null)
            return false;

        switch (step) {
            case PHONE:
                return phone != null && !phone.isBlank();

            case NAME:
                return name != null && !name.isBlank();

            case LICENSE:
                return licenseNumber != null && !licenseNumber.isBlank();

            case VEHICLE:
                return vehicleNumber != null && !vehicleNumber.isBlank();

            case COMPLETED:
                return true;

            default:
                return false;
        }
    }

}
