# Code Map

## Runtime

- `DeliveryInventoryServiceApplication` starts Spring Boot, Feign clients, scheduling, async, AOP, and dotenv loading.
- `pom.xml` uses Java 17, Spring Boot 3.3.4, Web, JPA, Validation, Redis, OpenFeign, Cloudinary, JWT, AOP, geohash, and Kafka.

## Controllers

- `Controller/OrderController.java` handles order creation, seed loading, distance matrix experiments, ETA refresh tests, and delivered status updates.
- `Controller/RiderController.java` handles rider signup, rider lookup/status changes, vehicle route assignment, and timing lookup.
- `Controller/admin/WarehouseAdminController.java` handles parcel lifecycle and shipment admin workflows.
- `Controller/admin/WarehouseController.java` handles warehouse CRUD-style access.
- `Controller/admin/ServiceablePincodeController.java` manages serviceable area records.
- `Controller/ServiceAvailabilityController.java` checks whether delivery service is available for an area.
- `Controller/DeliveryEstimateController.java` exposes delivery estimate lookup.
- `Controller/VehicleTrackingController.java` accepts location events for Kafka publishing.

## Services

- `OrderService` creates orders, assigns nearest warehouse, seeds orders from JSON, and contains route graph shortest-path logic.
- `ParcelService` manages parcel lifecycle, OTP transitions, rider assignment, warehouse handoff, and Redis-backed ETA data.
- `ShipmentService` creates shipments, attaches parcels, updates shipment state, and coordinates parcel shipment state.
- `RiderService` manages riders, vehicle data, uploaded documents/images, and route assignment.
- `WarehouseService` and `ServiceablePincodeService` provide admin operations.
- `DeliverySegmentService`, `EtaCalculationService`, `InterCityRouteService`, `InterCityRoutingEngine`, `GeohashCacheService`, and `RouteDelayService` support ETA, routing, geohash cache, and delay handling.
- `JwtService` signs/verifies JWTs.
- `NotificationService` sends OTP/notification-style requests through `RestTemplate`.

## Persistence

Repositories are Spring Data `JpaRepository` interfaces for:

- `Order`
- `Parcel`
- `Shipment`
- `Warehouse`
- `Rider`
- `Vehicle`
- `VehicleSchedule`
- `Route`
- `RoutePoint`
- `LocationEvent`
- `OtpLog`
- `ServiceablePincode`

## Domain Models

- Order lifecycle: `CREATED`, `PICKUP_SCHEDULED`, `PICKED`, `WAREHOUSE`, `IN_TRANSIT`, `DELIVERED`, `CANCELLED`, `PENDING`.
- Parcel lifecycle: `CREATED`, `AWAITING_PICKUP`, `PICKED_BY_RIDER`, `AT_WAREHOUSE`, `IN_SHIPMENT`, `IN_TRANSIT`, `AT_DEST_WAREHOUSE`, `OUT_FOR_DELIVERY`, `DELIVERED`, `RETURNED`, `FAILED`.
- Shipment lifecycle: `CREATED`, `ASSIGNED`, `PICKED_UP`, `IN_TRANSIT`, `ARRIVED`, `DELIVERED`, `CANCELLED`.
- Vehicle types: `FOUR_WHEELER`, `AUTO`, `PICKUP`, `TRAIN`, `BUS`, `MOTORCYCLE`.

## Messaging And Cache

- `kafka/BookingConfirmedConsumer.java` consumes booking confirmation events and routes them to order creation.
- `kafka/ParcelLifecycleProducer.java` publishes parcel lifecycle events.
- `tracking/VehicleLocationProducer.java` publishes vehicle location events.
- `tracking/VehicleLocationConsumer.java` consumes location events and updates route delay state.
- Redis is used for ETA/geohash/delay cache data.

## Utility Areas

- Routing: `RoutePathService`, `InterCityRouteService`, `InterCityRoutingEngine`.
- External distance/routing: `GoogleDistanceMatrix`, `GoogleClient`, `OsrmDistanceMatrix`, `OsrmRouteClient`.
- Optimization experiments: `KMeansClustering`, `GeneticAlgorithmSolver`, `VRPCapacitySolver`.
- Geo/cache helpers: `GeoUtils`, `GeohashService`, `GeohashUtils`, `EtaRedisKeys`, `EtaGeohashIndexService`.
- Cross-cutting: `GlobalExceptionHandler`, `JwtVerificationAspect`, `PrivateApi`, constants.

