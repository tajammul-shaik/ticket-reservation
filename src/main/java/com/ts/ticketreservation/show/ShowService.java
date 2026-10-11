package com.ts.ticketreservation.show;

import com.ts.ticketreservation.common.error.ApiException;
import com.ts.ticketreservation.common.error.ErrorCode;
import com.ts.ticketreservation.metrics.SeatGauges;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ShowService {

    static final int DEFAULT_PER_USER_LIMIT = 4;
    static final int MAX_SEATS_PER_SHOW = 10_000;

    private final ShowRepository showRepository;
    private final SeatGauges seatGauges;

    public ShowService(ShowRepository showRepository, SeatGauges seatGauges) {
        this.showRepository = showRepository;
        this.seatGauges = seatGauges;
    }

    /** FR-02: the show and all its seats are inserted in one transaction. */
    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {
        List<String> seats = request.seats();
        rejectDuplicateOrBlankLabels(seats);

        int perUserLimit = request.perUserLimit() == null ? DEFAULT_PER_USER_LIMIT : request.perUserLimit();
        var show = new ShowRepository.ShowRow(
                UUID.randomUUID().toString(),
                request.name().strip(),
                request.pricePaise(),
                perUserLimit,
                seats.size());

        showRepository.insertShow(show);
        showRepository.insertSeats(show.id(), seats);
        // Publish seat gauges only once the show is committed and visible to the scrape query.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                seatGauges.register(show.id());
            }
        });

        List<ShowResponse.SeatView> seatViews = seats.stream()
                .sorted()
                .map(label -> new ShowResponse.SeatView(label, "available"))
                .toList();
        return toResponse(show, seatViews);
    }

    /** FR-05. */
    @Transactional(readOnly = true)
    public ShowResponse getShow(String showId) {
        var show = showRepository.findShow(showId)
                .orElseThrow(() -> new ApiException(ErrorCode.SHOW_NOT_FOUND, "show " + showId + " not found"));
        return toResponse(show, showRepository.findSeats(showId));
    }

    private static void rejectDuplicateOrBlankLabels(List<String> seats) {
        Set<String> seen = new HashSet<>();
        for (String label : seats) {
            if (label.isBlank() || !label.equals(label.strip())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "seat label '" + label + "' must not be blank or have surrounding spaces");
            }
            if (!seen.add(label)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "duplicate seat label '" + label + "'");
            }
        }
    }

    private static ShowResponse toResponse(ShowRepository.ShowRow show, List<ShowResponse.SeatView> seats) {
        int available = 0;
        int held = 0;
        int confirmed = 0;
        for (var seat : seats) {
            switch (seat.status()) {
                case "available" -> available++;
                case "held" -> held++;
                case "confirmed" -> confirmed++;
                default -> throw new IllegalStateException("unknown seat status " + seat.status());
            }
        }
        var counts = new ShowResponse.Counts(available, held, confirmed);
        return new ShowResponse(
                show.id(),
                show.name(),
                show.pricePaise(),
                show.perUserLimit(),
                show.totalSeats(),
                counts,
                counts.sum() == show.totalSeats(),
                seats);
    }
}
