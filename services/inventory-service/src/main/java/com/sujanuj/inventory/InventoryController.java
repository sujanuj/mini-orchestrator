package com.sujanuj.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory inventory service. Deliberately simple: no database, no
 * persistence -- this exists to be a realistic *dependency* for
 * orders-service and a realistic target for the orchestrator (Phase 2+),
 * not to demonstrate data-layer design.
 *
 * Concurrency note: stock is a ConcurrentHashMap and reserve() uses
 * compute() to make "check quantity, then decrement" a single atomic
 * operation. Two naive separate get()-then-put() calls under concurrent
 * requests (e.g. from multiple orders-service replicas, or the
 * orchestrator load-balancing across multiple inventory-service
 * containers) would race and could oversell stock -- worth getting right
 * here since this service is explicitly meant to be run with multiple
 * replicas once the orchestrator exists.
 */
@RestController
public class InventoryController {

    private final Map<String, Integer> stock = new ConcurrentHashMap<>();

    public InventoryController() {
        // Seed data. A real service would load this from a database;
        // deliberately in-memory and hardcoded here to keep this
        // service's only job being "a realistic thing to orchestrate."
        stock.put("widget", 100);
        stock.put("gadget", 50);
        stock.put("gizmo", 0); // intentionally zero, to exercise the "out of stock" path
    }

    public record StockResponse(String item, int quantity) {}

    public record ReserveRequest(String item, int quantity) {}

    public record ReserveResponse(String item, int reserved, int remaining) {}

    public record ErrorResponse(String error) {}

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/inventory")
    public ResponseEntity<Map<String, Integer>> listAll() {
        return ResponseEntity.ok(Map.copyOf(stock));
    }

    @GetMapping("/inventory/{item}")
    public ResponseEntity<?> getItem(@PathVariable String item) {
        Integer quantity = stock.get(item);
        if (quantity == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("no such item: " + item));
        }
        return ResponseEntity.ok(new StockResponse(item, quantity));
    }

    /**
     * Atomically reserves (decrements) stock for an item. Returns 409
     * Conflict rather than 400 Bad Request when stock is insufficient --
     * this is a state conflict (the request is well-formed, the current
     * state just can't satisfy it), which is the more precise HTTP
     * semantic and what orders-service's error handling below is written
     * against.
     */
    @PostMapping("/inventory/reserve")
    public ResponseEntity<?> reserve(@RequestBody ReserveRequest request) {
        if (request.quantity() <= 0) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("quantity must be positive"));
        }

        // AtomicReference-style trick via array, since compute()'s lambda
        // can't easily return two different response types directly --
        // compute the new stock level atomically, then decide the HTTP
        // response outside the lambda based on whether it succeeded.
        final boolean[] succeeded = {false};
        final int[] remainingAfter = {0};

        stock.compute(request.item(), (key, currentQuantity) -> {
            if (currentQuantity == null) {
                return null; // item doesn't exist; leave map unchanged
            }
            if (currentQuantity < request.quantity()) {
                remainingAfter[0] = currentQuantity;
                return currentQuantity; // insufficient stock; leave unchanged
            }
            succeeded[0] = true;
            int newQuantity = currentQuantity - request.quantity();
            remainingAfter[0] = newQuantity;
            return newQuantity;
        });

        if (!stock.containsKey(request.item())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("no such item: " + request.item()));
        }
        if (!succeeded[0]) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(
                            "insufficient stock for " + request.item()
                                    + ": requested " + request.quantity()
                                    + ", available " + remainingAfter[0]));
        }

        return ResponseEntity.ok(
                new ReserveResponse(request.item(), request.quantity(), remainingAfter[0]));
    }
}
