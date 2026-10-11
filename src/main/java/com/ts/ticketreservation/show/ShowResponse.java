package com.ts.ticketreservation.show;

import java.util.List;

/** FR-05 show state. Holder user ids are deliberately not part of it. */
public record ShowResponse(
        String id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        Counts counts,
        boolean invariantOk,
        List<SeatView> seats) {

    public record Counts(int available, int held, int confirmed) {

        int sum() {
            return available + held + confirmed;
        }
    }

    public record SeatView(String label, String status) {
    }
}
