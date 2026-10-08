package com.navan.receipts.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

/** Request body for PATCH /transactions/{id}/items: the full replacement set of line items. */
public record ItemsPatchRequest(@Valid List<LineItemPatch> lineItems) {

    public record LineItemPatch(
            @NotBlank String description,
            @NotNull @PositiveOrZero BigDecimal amount) {}
}
