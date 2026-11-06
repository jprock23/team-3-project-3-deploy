/** Defines legacy database record objects for the original C++ prototype. */

#pragma once
#include <cstdint>
#include <ctime>

struct {
    uint32_t shipmentId;
    uint32_t inventoryId;
    double wholesalePrice;
    int32_t quantity;
    time_t expirationDate;
} menuItem;

struct {
    uint32_t shipmentId;
    uint32_t inventoryId;
    double wholesalePrice;
    uint32_t quantity;
    time_t expirationDate;
} inventoryItem;

struct {
    uint32_t menuItemId;
    uint32_t inventoryId;
    uint32_t quantityRequired;
} menuItemInventory;

struct {
    uint32_t orderId;
    time_t orderDate;
    uint32_t orderTime;
    double total;
} order;

struct {
    uint32_t orderItemId;
    uint32_t orderId;
    uint32_t menuItemId;
    uint32_t qty;
} orderItems;
