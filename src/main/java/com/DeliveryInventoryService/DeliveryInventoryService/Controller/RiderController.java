package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.RiderSignupDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.RiderService;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.annotation.PrivateApi;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/riders")
public class RiderController {

    private final RiderService riderService;

    public RiderController(RiderService riderService) {
        this.riderService = riderService;
    }

    /**
     * Signup step API - progresses the rider onboarding step by step
     */
    @PostMapping("/signup")
    @PrivateApi
    public ResponseEntity<?> signupStep(@RequestBody @Valid RiderSignupDTO riderRequest) {
        try {
            ApiResponse<Object> response = riderService.signupStep(riderRequest);
            return ResponseEntity.status(response.statusCode()).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ApiResponse<>(false, e.getMessage(), null, 400));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new ApiResponse<>(false, e.getMessage(), null, 500));
        }
    }

    /**
     * Get rider by phone - frontend can check current step
     */
    @GetMapping("/{phone}")
    public ResponseEntity<Rider> getRider(@PathVariable String phone) {
        try {
            Rider rider = riderService.getRiderByPhone(phone);
            return ResponseEntity.ok(rider);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Mark rider inactive
     */
    @PutMapping("/{phone}/inactive")
    public ResponseEntity<Rider> markInactive(@PathVariable String phone) {
        try {
            Rider rider = riderService.markInactive(phone);
            return ResponseEntity.ok(rider);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Blacklist rider
     */
    @PutMapping("/{phone}/blacklist")
    public ResponseEntity<Rider> blacklist(@PathVariable String phone) {
        try {
            Rider rider = riderService.blacklist(phone);
            return ResponseEntity.ok(rider);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
// hihui kjhiu hiu hhn hhgjhgjh hkjhknjj jhhjjkhiuuib
// uijkyi8 hiiyhkuiy8iu8ifiurhhuighbhyig hiyb u8utg u98utrnk5trhuutljnhhhuhuhhhh
// hhh hhhhu jju jjuj