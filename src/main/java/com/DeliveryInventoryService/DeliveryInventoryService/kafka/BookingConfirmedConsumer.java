package com.DeliveryInventoryService.DeliveryInventoryService.kafka;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingConfirmedConsumer {

    private final OrderService orderService;

    @KafkaListener(
            topics = "${app.kafka.topic.booking-confirmed:booking.confirmed}",
            groupId = "delivery-booking-group",
            containerFactory = "bookingConfirmedListenerContainerFactory"
    )
    public void consume(BookingConfirmedEvent event) {
        log.info("Received BookingConfirmedEvent | bookingId={} customerId={}",
                event.getBookingId(), event.getCustomerId());

        Order order = new Order();
        order.setBookingId(event.getBookingId());
        order.setCustomerId(event.getCustomerId());
        // Store address UUIDs as string references — resolved later during parcel assignment
        order.setOriginAddress(event.getShopId().toString());
        order.setDestAddress(event.getDeliveryAddressId().toString());
        order.setServiceType(Order.ServiceType.STANDARD);
        order.setStatus(Order.OrderStatus.CREATED);

        try {
            Order saved = orderService.createOrder(order);
            log.info("Order created from booking | orderId={} orderNo={} bookingId={}",
                    saved.getId(), saved.getOrderNo(), event.getBookingId());
        } catch (Exception e) {
            log.error("Failed to create order from BookingConfirmedEvent | bookingId={}",
                    event.getBookingId(), e);
            throw e; // re-throw so Kafka retries the message
        }
    }
}
