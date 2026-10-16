package bank.user.controller;

import bank.user.service.TransactionProxy;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionProxy proxy;

    @GetMapping("/{accountId}/transactions")
    @PreAuthorize("hasAnyRole('ADMIN', 'AGENT')")
    public ResponseEntity<JsonNode> getTransactions(
            @PathVariable UUID accountId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization
    ) {
        return proxy.getTransactions(accountId, authorization);
    }
}
