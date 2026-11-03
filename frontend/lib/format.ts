export function formatMinorUnits(
  amountMinor: string | null | undefined,
  currencyCode: string | null | undefined,
  exponent: number | null | undefined,
): string {
  if (amountMinor == null || currencyCode == null) return "—";
  if (exponent == null) return `${amountMinor} ${currencyCode} minor units`;
  if (!/^-?\d+$/.test(amountMinor) || !Number.isInteger(exponent) || exponent < 0 || exponent > 6) {
    return `${amountMinor} ${currencyCode} minor units`;
  }
  const value = BigInt(amountMinor);
  const negative = value < 0n;
  const absolute = negative ? -value : value;
  const divisor = 10n ** BigInt(exponent);
  const major = absolute / divisor;
  const fraction = exponent === 0 ? "" : `.${(absolute % divisor).toString().padStart(exponent, "0")}`;
  const grouped = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 }).format(major);
  return `${negative ? "−" : ""}${currencyCode} ${grouped}${fraction}`;
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? "—" : new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium", timeStyle: "short",
  }).format(date);
}
