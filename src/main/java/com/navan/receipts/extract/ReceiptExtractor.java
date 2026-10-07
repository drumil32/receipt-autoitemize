package com.navan.receipts.extract;

import com.navan.receipts.domain.ItemizeStatus;
import com.navan.receipts.error.UnprocessableEntityException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Parses stubbed OCR text into a structured receipt. Method is line-based (no vendor call). */
@Component
public class ReceiptExtractor {

    /** A money amount with two decimals, e.g. 17.85. */
    private static final Pattern MONEY = Pattern.compile("\\d+\\.\\d{2}");
    /** A tax label and its percentage, e.g. "VAT 19%". */
    private static final Pattern TAX = Pattern.compile("([A-Za-z]{2,})\\s+(\\d+(?:\\.\\d+)?)\\s*%");

    public ExtractedReceipt extract(String ocrText) {
        String merchant = null;
        LocalDate date = null;
        String currency = null;
        BigDecimal grandTotal = null;
        List<ExtractedTax> taxes = new ArrayList<>();
        List<ExtractedLineItem> items = new ArrayList<>();

        for (String raw : ocrText.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            String upper = line.toUpperCase(Locale.ROOT);

            if (upper.startsWith("MERCHANT:")) {
                merchant = valueAfterColon(line);
            } else if (upper.startsWith("DATE:")) {
                date = parseDate(valueAfterColon(line));
            } else if (upper.startsWith("CURRENCY:")) {
                currency = valueAfterColon(line);
            } else if (upper.startsWith("TOTAL")) {
                grandTotal = lastMoney(line);
            } else if (upper.startsWith("SUBTOTAL")) {
                // intermediate sum, not persisted
            } else if (line.contains("%")) {
                parseTax(line).ifPresent(taxes::add);
            } else if (line.startsWith("(")) {
                // parenthetical note, e.g. "(No itemized list)"
            } else {
                parseLineItem(line).ifPresent(items::add);
            }
        }

        if (merchant == null || date == null || currency == null || grandTotal == null) {
            throw new UnprocessableEntityException(
                    "Could not extract required fields (merchant, date, currency, total)");
        }

        ItemizeStatus status = itemizeStatus(items, taxes, grandTotal);
        return new ExtractedReceipt(merchant, date, currency, grandTotal, taxes, items, status);
    }

    private static ItemizeStatus itemizeStatus(
            List<ExtractedLineItem> items, List<ExtractedTax> taxes, BigDecimal grandTotal) {
        if (items.isEmpty()) {
            return ItemizeStatus.NEEDS_REVIEW;
        }
        BigDecimal itemsTotal = sum(items.stream().map(ExtractedLineItem::amount).toList());
        BigDecimal taxesTotal = sum(taxes.stream().map(ExtractedTax::amount).toList());
        return Reconciliation.reconciles(itemsTotal, taxesTotal, grandTotal)
                ? ItemizeStatus.COMPLETE
                : ItemizeStatus.NEEDS_REVIEW;
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String valueAfterColon(String line) {
        return line.substring(line.indexOf(':') + 1).strip();
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null; // treated as a missing required field below
        }
    }

    /** Last two-decimal amount on the line, or null if none. */
    private static BigDecimal lastMoney(String line) {
        Matcher m = MONEY.matcher(line);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        return last == null ? null : new BigDecimal(last);
    }

    private static java.util.Optional<ExtractedTax> parseTax(String line) {
        Matcher m = TAX.matcher(line);
        BigDecimal amount = lastMoney(line);
        if (!m.find() || amount == null) {
            return java.util.Optional.empty();
        }
        BigDecimal rate = new BigDecimal(m.group(2)).movePointLeft(2); // 19 -> 0.19
        return java.util.Optional.of(new ExtractedTax(m.group(1), rate, amount));
    }

    private static java.util.Optional<ExtractedLineItem> parseLineItem(String line) {
        Matcher m = MONEY.matcher(line);
        int amountStart = -1;
        String amount = null;
        while (m.find()) {
            amountStart = m.start();
            amount = m.group();
        }
        if (amount == null) {
            return java.util.Optional.empty(); // no amount -> not an item (e.g. "Trip fare")
        }
        String description = line.substring(0, amountStart).strip();
        return java.util.Optional.of(new ExtractedLineItem(description, new BigDecimal(amount)));
    }
}
