package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

public record ApiResponse<T>(boolean success, String message, T data, int statusCode) {
}