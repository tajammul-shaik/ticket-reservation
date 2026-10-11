package com.ts.ticketreservation.show;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/** FR-02 request body. Seat-label uniqueness is checked in {@link ShowService}. */
public record CreateShowRequest(
        @NotBlank @Size(max = 100) String name,
        @NotEmpty @Size(max = ShowService.MAX_SEATS_PER_SHOW) List<@NotNull @Size(min = 1, max = 8) String> seats,
        @NotNull @PositiveOrZero Long pricePaise,
        @Min(1) @Max(ShowService.MAX_SEATS_PER_SHOW) Integer perUserLimit) {
}
