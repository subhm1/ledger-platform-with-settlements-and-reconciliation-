package com.ledger.platform.api;

import com.ledger.platform.service.WalletTransferService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/wallets")
public final class WalletController {
    private final WalletTransferService service;

    public WalletController(WalletTransferService service) {
        this.service = service;
    }

    @GetMapping
    public List<WalletModels.Wallet> list() {
        return service.listDemoWallets();
    }

    @PostMapping("/transfers")
    public ResponseEntity<WalletModels.TransferReceipt> transfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody WalletModels.TransferRequest request,
            Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.transfer(request, idempotencyKey, principal.getName()));
    }
}
