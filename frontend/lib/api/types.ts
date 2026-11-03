export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type TransactionSummary = {
  transactionId: string;
  transactionReference: string;
  currencyCode: string;
  minorUnitExponent: number;
  totalDebitMinor: string;
  status: string;
  postedAt: string;
  reversalOfTransactionId: string | null;
};

export type TransactionDetail = TransactionSummary & {
  description: string;
  entries: Array<{
    entryId: string;
    accountId: string;
    accountCode: string;
    accountDisplayName: string;
    accountRole: string;
    direction: "DEBIT" | "CREDIT";
    amountMinor: string;
    currencyCode: string;
    minorUnitExponent: number;
  }>;
};

export type SettlementBatch = {
  batchId: string;
  sourceCode: string;
  reportId: string;
  fileSha256: string;
  status: string;
  reportingPeriodStart: string;
  reportingPeriodEnd: string;
  createdAt: string;
  recordCount: number;
  validRecordCount: number;
  invalidRecordCount: number;
};

export type SettlementRecord = {
  recordId: string;
  rowNumber: number;
  externalTransactionId: string | null;
  transactionReference: string | null;
  currencyCode: string | null;
  minorUnitExponent: number | null;
  grossAmountMinor: string | null;
  feeAmountMinor: string | null;
  adjustmentAmountMinor: string | null;
  netAmountMinor: string | null;
  validationStatus: string;
  validationErrors: string[];
};

export type ReconciliationRun = {
  runId: string;
  batchId: string;
  runNumber: number;
  ruleVersion: string;
  status: string;
  startedAt: string;
  completedAt: string | null;
};

export type ReconciliationResult = {
  resultId: string;
  batchId: string;
  runId: string;
  externalRecordId: string | null;
  internalTransactionId: string | null;
  outcome: string;
  transactionReference: string | null;
  currencyCode: string | null;
  minorUnitExponent: number | null;
  reportedGrossMinor: string | null;
  internalDebitTotalMinor: string | null;
  ruleVersion: string | null;
  validationErrors: string[];
  createdAt: string;
};

export type ApiErrorBody = { code?: string; message?: string };
export type HealthDocument = {
  status?: string;
  components?: Record<string, { status?: string }>;
};

export type DemoWallet = {
  walletId: string;
  displayName: string;
  currencyCode: string;
  minorUnitExponent: number;
  status: "ACTIVE" | "UNAVAILABLE";
  balanceMinor: string;
};

export type WalletTransferReceipt = {
  transactionId: string;
  transactionReference: string;
  sourceWalletId: string;
  sourceName: string;
  destinationWalletId: string;
  destinationName: string;
  amountMinor: string;
  currencyCode: string;
  minorUnitExponent: number;
  status: "POSTED";
  postedAt: string;
};

export type ProviderAttempt = {
  attemptId: string;
  receipt: string;
  amountMinor: string;
  currencyCode: string;
  purpose: string;
  businessReference: string;
  state: string;
  providerOrderId: string | null;
  providerPaymentId: string | null;
  createdAt: string;
  updatedAt: string;
};
export type ProviderAttemptPage = {
  items: ProviderAttempt[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  checkoutConfigured: boolean;
  checkoutKeyId: string;
};
export type ProviderAttemptEvent = {
  eventId: string;
  eventType: string;
  previousState: string | null;
  newState: string;
  safeDetail: string;
  occurredAt: string;
};
export type ProviderAttemptDetail = {
  attempt: ProviderAttempt;
  accountingState: string;
  events: ProviderAttemptEvent[];
};
export type ProviderCreateResponse = {
  attempt: ProviderAttempt;
  checkout: null | { attemptId: string; providerOrderId: string; keyId: string; amountMinor: string; currencyCode: string };
};

export type ProviderWebhookEvent = {
  id: string;
  providerEventId: string;
  eventType: string;
  providerOrderId: string | null;
  providerPaymentId: string | null;
  processingState: "RECEIVED" | "PROCESSING" | "PROCESSED" | "RETRY_WAIT" | "REVIEW_REQUIRED" | string;
  attemptCount: number;
  safeErrorCode: string | null;
  receivedAt: string;
  processedAt: string | null;
};
export type ProviderWebhookEventPage = Page<ProviderWebhookEvent>;

export type ProviderPaymentReconciliationRun = {
  id: string;
  runNumber: number;
  ruleVersion: string;
  status: "RUNNING" | "COMPLETED" | "FAILED" | string;
  totalInternalRecords: number;
  totalProviderRecords: number;
  matchedCount: number;
  unmatchedInternalCount: number;
  unmatchedProviderCount: number;
  duplicateCount: number;
  conflictingCount: number;
  startedAt: string;
  completedAt: string | null;
};

export type ProviderPaymentReconciliationResult = {
  id: string;
  runId: string;
  attemptId: string | null;
  webhookEventId: string | null;
  providerPaymentId: string | null;
  providerOrderId: string | null;
  outcome: "MATCHED" | "UNMATCHED_INTERNAL" | "UNMATCHED_PROVIDER" | "DUPLICATE" | "CONFLICTING";
  discrepancyReason: string | null;
  details: string;
  createdAt: string;
};
