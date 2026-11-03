package com.ledger.platform.api;

import java.time.Instant;
import java.util.UUID;

public final class WalletModels {
    private WalletModels() { }

    public record Wallet(
            UUID walletId,
            String displayName,
            String currencyCode,
            int minorUnitExponent,
            String status,
            String balanceMinor) { }

    public record TransferRequest(
            UUID sourceWalletId,
            UUID destinationWalletId,
            Long amountMinor,
            String transactionReference,
            String reference) { }

    public record TransferReceipt(
            UUID transactionId,
            String transactionReference,
            UUID sourceWalletId,
            String sourceName,
            UUID destinationWalletId,
            String destinationName,
            String amountMinor,
            String currencyCode,
            int minorUnitExponent,
            String status,
            Instant postedAt) { }
}
