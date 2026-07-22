package com.sujanuj.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places orders by calling inventory-service to reserve stock. This
 * service exists specifically to demonstrate real inter-service failure
 * modes under the orchestrator (Phase 2+) -- what happens to a caller
 * when its dependency is killed, restarted, or genuinely out of stock
 * are three DIFFERENT outcomes, deliberately distinguished below rather
 * than collapsed into one generic "error" response.
 */
@RestController
public class OrdersController {

    private final RestTemplate restTemplate;
    private final String inventoryServiceUrl;

    // In-memory order log. Same deliberate simplicity as inventory-service's
    // stock map -- this project's point is the orchestrator, not a
    // production order-management data model.
    private final Map<String, OrderRecord> orders = new ConcurrentHashMap<>();

    public OrdersController(
            RestTemplate restTemplate,
            @Value("${inventory.service.url}") String inventoryServiceUrl) {
        this.restTemplate = restTemplate;
        this.inventoryServiceUrl = inventoryServiceUrl;
    }

    public record OrderRequest(String item, int quantity) {}

    public record OrderRecord(String orderId, String item, int quantity, String status) {}

    public record ErrorResponse(String error) {}

    private record ReserveRequest(String item, int quantity) {}

    private record ReserveResponse(String item, int reserved, int remaining) {}

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        // Deliberately does NOT call inventory-service here. A "deep"
        // health check that depends on a downstream service would make
        // orders-service report unhealthy whenever inventory-service is
        // being restarted by the orchestrator -- which is exactly the
        // kind of cascading-failure amplification a real health check
        // should avoid. orders-service being "up" and orders-service
        // being "able to reach inventory right now" are two different
        // facts; only the first belongs in /health.
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable String orderId) {
        OrderRecord order = orders.get(orderId);
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("no such order: " + orderId));
        }
        return ResponseEntity.ok(order);
    }

    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(@RequestBody OrderRequest request) {
        if (request.quantity() <= 0) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("quantity must be positive"));
        }

        String orderId = UUID.randomUUID().toString();

        try {
            ReserveResponse reserved = restTemplate.postForObject(
                    inventoryServiceUrl + "/inventory/reserve",
                    new ReserveRequest(request.item(), request.quantity()),
                    ReserveResponse.class);

            OrderRecord order = new OrderRecord(
                    orderId, request.item(), request.quantity(), "CONFIRMED");
            orders.put(orderId, order);
            return ResponseEntity.status(HttpStatus.CREATED).body(order);

        } catch (HttpClientErrorException.Conflict e) {
            // inventory-service correctly reported insufficient stock --
            // a legitimate business outcome, not an infrastructure
            // failure. The order is recorded as REJECTED rather than
            // silently dropped, so GET /orders/{id} has something to
            // show for it.
            OrderRecord order = new OrderRecord(
                    orderId, request.item(), request.quantity(), "REJECTED_OUT_OF_STOCK");
            orders.put(orderId, order);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(order);

        } catch (HttpClientErrorException.NotFound e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("unknown item: " + request.item()));

        } catch (ResourceAccessException e) {
            // inventory-service is unreachable -- connection refused
            // (container is down/restarting) or the 2s connect timeout /
            // 3s read timeout from RestTemplateConfig was hit. This is
            // precisely the failure mode the orchestrator's auto-restart
            // (Phase 3) is meant to make transient rather than permanent:
            // a request that lands in this exact window should fail fast
            // and clearly, and a retry moments later (once the
            // orchestrator has restarted the container) should succeed.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse(
                            "inventory-service unavailable: " + e.getMessage()));
        }
    }
}
