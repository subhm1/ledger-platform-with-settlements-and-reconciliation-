import { describe, expect, it } from "vitest";
import { formatMinorUnits } from "./format";

describe("formatMinorUnits", () => {
  it("preserves very large minor unit values exactly", () => {
    expect(formatMinorUnits("900719925474099312345", "USD", 2)).toBe("USD 9,007,199,254,740,993,123.45");
  });

  it("uses zero and non-two currency exponents without rounding", () => {
    expect(formatMinorUnits("1234", "JPY", 0)).toBe("JPY 1,234");
    expect(formatMinorUnits("123456", "KWD", 3)).toBe("KWD 123.456");
  });

  it("distinguishes missing amounts and unknown currency precision", () => {
    expect(formatMinorUnits(null, "USD", 2)).toBe("—");
    expect(formatMinorUnits("123", "USD", null)).toBe("123 USD minor units");
    expect(formatMinorUnits("123", "USD", 8)).toBe("123 USD minor units");
  });
});
