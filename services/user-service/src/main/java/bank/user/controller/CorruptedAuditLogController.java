package bank.user.controller;

import bank.user.service.CorruptedAuditLogProxy;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/corrupted-logs")
@RequiredArgsConstructor
public class CorruptedAuditLogController {

    private final CorruptedAuditLogProxy proxy;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JsonNode> getCorruptedLogs() {
        return proxy.getCorruptedLogs();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JsonNode> createCorruptedLog(@RequestBody JsonNode request) {
        return proxy.createCorruptedLog(request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JsonNode> deleteCorruptedLog(@PathVariable UUID id) {
        return proxy.deleteCorruptedLog(id);
    }
}
