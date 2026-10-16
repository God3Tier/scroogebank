package bank.user.controller;

import bank.user.service.TransactionProxy;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every account's transactions, for admins only (ADR-0043, requirements Q&A
 * TM14). Agents read transactions per account through
 * {@link TransactionController}, which checks they own the account.
 */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionListController {

    private final TransactionProxy proxy;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JsonNode> listTransactions(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status
    ) {
        return proxy.listTransactions(cursor, size, status);
    }
}
