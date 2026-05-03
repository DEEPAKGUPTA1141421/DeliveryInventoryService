package com.DeliveryInventoryService.DeliveryInventoryService.Config;

import com.DeliveryInventoryService.DeliveryInventoryService.kafka.BookingConfirmedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${app.kafka.topic.vehicle-location:vehicle.location.update}")
    private String vehicleLocationTopic;

    @Value("${app.kafka.topic.booking-confirmed:booking.confirmed}")
    private String bookingConfirmedTopic;

    /**
     * 6 partitions — one per active vehicle group; allows 6 consumer instances to
     * process location pings in parallel without violating per-route ordering
     * (producers key by routeId, so all pings for a route land on the same partition).
     */
    @Bean
    public NewTopic vehicleLocationTopic() {
        return TopicBuilder.name(vehicleLocationTopic)
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic bookingConfirmedTopic() {
        return TopicBuilder.name(bookingConfirmedTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public ConsumerFactory<String, BookingConfirmedEvent> bookingConfirmedConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "delivery-booking-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        JsonDeserializer<BookingConfirmedEvent> deserializer =
                new JsonDeserializer<>(BookingConfirmedEvent.class);
        deserializer.addTrustedPackages(
                "com.OrderPaymentNotificationService.OrderPaymentNotificationService.DTO.delivery",
                "com.DeliveryInventoryService.DeliveryInventoryService.kafka"
        );
        deserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BookingConfirmedEvent>
    bookingConfirmedListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, BookingConfirmedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(bookingConfirmedConsumerFactory());
        factory.setConcurrency(3);
        return factory;
    }
}
