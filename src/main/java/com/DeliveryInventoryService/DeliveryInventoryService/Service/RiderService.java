package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.RiderSignupDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.RouteDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.RoutePointDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.network.RiderIdResponseDto;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider.RiderStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RiderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RoutePointRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RouteRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VehicleRepository;
import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class RiderService {

    private final RiderRepository riderRepository;
    private final Cloudinary cloudinary;
    private final HttpServletRequest httpRequest;
    private final VehicleRepository vehicleRepository;
    private final RouteRepository routeRepository;
    private final RoutePointRepository routePointRepository;

    public ApiResponse<Object> signupStep(RiderSignupDTO request) {
        Rider rider = riderRepository.findByPhone(request.phone()).orElse(null);
        UUID riderId = UUID.fromString(httpRequest.getAttribute("id").toString());
        if (rider == null && request.step() == Rider.RiderStep.PHONE) {
            rider = new Rider();
            rider.setPhone(request.phone());
            rider.setStep(Rider.RiderStep.PHONE);
            rider = riderRepository.save(rider);
        } else {
            rider = riderRepository.findById(riderId).orElse(null);
        }
        switch (request.step()) { // step comes from client
            case PHONE:
                return handlePhone(rider, request);

            case NAME:
                if (!(rider.getStep() == Rider.RiderStep.PHONE)) {
                    return new ApiResponse<>(false, "You must complete PHONE step first", null, 400);
                }
                return handleName(rider, request);

            case LICENSE:
                if (!(rider.getStep() == Rider.RiderStep.NAME)) {
                    return new ApiResponse<>(false, "You must complete NAME step first", null, 400);
                }
                return handleLicense(rider, request);

            case VEHICLE:
                if (!(rider.getStep() == Rider.RiderStep.LICENSE)) {
                    return new ApiResponse<>(false, "You must complete LICENSE step first", null, 400);
                }
                return handleVehicle(rider, request);

            case VEHICLE_TYPE:
                if (rider.getStep() == Rider.RiderStep.VEHICLE) {
                    return new ApiResponse<>(false, "You must complete VEHICLE step first", null, 400);
                }
                return handleVehicleType(rider, request);

            case VEHICLE_IMAGE:
                if (rider.getStep() == Rider.RiderStep.VEHICLE_TYPE) {
                    return new ApiResponse<>(false, "You must complete VEHICLE_TYPE step first", null, 400);
                }
                return handleVehicleImages(rider, request);
            default:
                return new ApiResponse<>(false, "Invalid step", null, 400);
        }
    }

    private ApiResponse<Object> handlePhone(Rider rider, RiderSignupDTO request) {
        return new ApiResponse<>(true, "Phone saved", new RiderIdResponseDto(rider.getId()), 200);
    }

    private ApiResponse<Object> handleName(Rider rider, RiderSignupDTO request) {
        rider.setName(request.name());
        rider.setStep(Rider.RiderStep.NAME); // keep NAME
        riderRepository.save(rider);
        return new ApiResponse<>(true, "Name saved", rider, 200);
    }

    private ApiResponse<Object> handleLicense(Rider rider, RiderSignupDTO request) {
        Vehicle vehicle = rider.getVehicle();

        if (vehicle == null) {
            vehicle = new Vehicle();
            vehicle.setRider(rider); // establish relation
        }

        vehicle.setLicenseNumber(request.licenseNumber());
        rider.setStep(Rider.RiderStep.LICENSE);

        // save both Rider and Vehicle
        rider.setVehicle(vehicle);
        riderRepository.save(rider);

        return new ApiResponse<>(true, "License saved", rider, 200);
    }

    private ApiResponse<Object> handleVehicle(Rider rider, RiderSignupDTO request) {
        Vehicle vehicle = rider.getVehicle();

        if (vehicle == null) {
            vehicle = new Vehicle();
            vehicle.setRider(rider);
        }

        vehicle.setVehicleNumber(request.vehicleNumber());

        rider.setVehicle(vehicle);

        riderRepository.save(rider);

        return new ApiResponse<>(true, "Rider onboarding completed", rider, 200);
    }

    public ApiResponse<Object> handleVehicleImages(Rider rider, RiderSignupDTO request) {
        List<MultipartFile> files = request.files();
        Vehicle vehicle = rider.getVehicle();
        if (vehicle == null) {
            vehicle = new Vehicle();
            vehicle.setRider(rider);
        }

        List<String> uploadedUrls = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                Map uploadResult = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.emptyMap());
                uploadedUrls.add(uploadResult.get("url").toString());
            } catch (IOException e) {
                return new ApiResponse<>(false, "Failed to upload vehicle image", null, 500);
            }
        }

        vehicle.getImages().addAll(uploadedUrls);
        rider.setVehicle(vehicle);
        riderRepository.save(rider);

        return new ApiResponse<>(true, "Vehicle images uploaded", rider, 200);
    }

    public ApiResponse<Object> createRoute(UUID vehicleId, RouteDTO routeDTO) {
        try {
            Vehicle vehicle = vehicleRepository.findById(vehicleId)
                    .orElseThrow(() -> new RuntimeException("Vehicle not found"));

            Route route = new Route();
            route.setVehicle(vehicle);
            route.setStartLocation(routeDTO.startLocation());
            route.setDestinationLocation(routeDTO.destinationLocation());
            route.setStartTime(routeDTO.startTime());
            route.setEndTime(routeDTO.endTime());

            routeRepository.save(route);

            // Save route points
            if (routeDTO.points() != null && !routeDTO.points().isEmpty()) {
                List<RoutePoint> points = new ArrayList<>();
                int seq = 1;
                for (RoutePointDTO p : routeDTO.points()) {
                    RoutePoint point = new RoutePoint();
                    point.setRoute(route);
                    point.setSequence(seq++);
                    point.setLocationName(p.locationName());
                    point.setLatitude(p.latitude());
                    point.setLongitude(p.longitude());
                    points.add(point);
                }
                routePointRepository.saveAll(points);
            }

            return new ApiResponse<>(true, "Route created successfully", route, 201);
        } catch (Exception e) {
            return new ApiResponse<>(false, "Failed to create route: " + e.getMessage(), null, 500);
        }
    }

    public ApiResponse<Object> handleVehicleType(Rider rider, RiderSignupDTO request) {
        String vehicleTypeStr = request.vehicleTypeStr();
        Vehicle vehicle = rider.getVehicle();
        if (vehicle == null) {
            vehicle = new Vehicle();
            vehicle.setRider(rider);
        }

        try {
            Vehicle.VehicleType vehicleType = Vehicle.VehicleType.valueOf(vehicleTypeStr.toUpperCase());
            vehicle.setVehicleType(vehicleType);
        } catch (IllegalArgumentException e) {
            return new ApiResponse<>(false, "Invalid vehicle type: " + vehicleTypeStr, null, 400);
        }
        rider.setStep(Rider.RiderStep.COMPLETED); // final step
        rider.setStatus(Rider.RiderStatus.ACTIVE);

        rider.setVehicle(vehicle);
        riderRepository.save(rider);

        return new ApiResponse<>(true, "Vehicle type updated", rider, 200);
    }

    public Rider getRiderByPhone(String phone) {
        return riderRepository.findByPhone(phone)
                .orElseThrow(() -> new IllegalArgumentException("Rider not found"));
    }

    public Rider markInactive(String phone) {
        Rider rider = getRiderByPhone(phone);
        rider.setStatus(RiderStatus.INACTIVE);
        return riderRepository.save(rider);
    }

    public Rider blacklist(String phone) {
        Rider rider = getRiderByPhone(phone);
        rider.setStatus(RiderStatus.BLACKLISTED);
        return riderRepository.save(rider);
    }

    public ApiResponse<Object> findTimeOfProduct(List<UUID> productIds) {
        try {
            UUID productId = productIds.get(0);
            UUID userId = UUID.fromString(httpRequest.getAttribute("id").toString());

            // we have to find distance in three way
            // 1 time to collect the parcel and move to warhouse;

            // 2 time to load the parcel to ship to the user's warehouse
            // 3 time to ship the parcel to user's location

            return new ApiResponse<>(true, "Time of product found", productId, 200);
        } catch (Exception e) {
            return new ApiResponse<>(false, "Failed to find time of product: " + e.getMessage(), null, 500);
        }
    }
}

// hhg6tgjh gy gyyg kh uujh huhuj ujhh huhju