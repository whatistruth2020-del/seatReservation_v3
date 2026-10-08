package com.paytm.seatreservation.service;

import java.time.OffsetDateTime;
import java.util.List;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.paytm.seatreservation.config.AppProperties;

import com.paytm.seatreservation.dto.ConfirmResponse;
import com.paytm.seatreservation.dto.ReserveRequest;
import com.paytm.seatreservation.dto.ReserveResponse;
import com.paytm.seatreservation.entity.Reservation;
import com.paytm.seatreservation.entity.ReservationSeat;
import com.paytm.seatreservation.entity.ReservationStatus;
import com.paytm.seatreservation.entity.Seat;
import com.paytm.seatreservation.entity.SeatStatus;
import com.paytm.seatreservation.entity.Show;
import com.paytm.seatreservation.entity.UserBookingLimit;
import com.paytm.seatreservation.exception.SeatReservationException;
import com.paytm.seatreservation.repository.ReservationRepository;
import com.paytm.seatreservation.repository.ReservationSeatRepository;
import com.paytm.seatreservation.repository.SeatRepository;
import com.paytm.seatreservation.repository.ShowRepository;
import com.paytm.seatreservation.repository.UserBookingLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationTransactionService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final UserBookingLimitRepository userBookingLimitRepository;
    private final AppProperties appProperties;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReserveResponse reserveInTransaction(
            Long showId,
            Long userId,
            ReserveRequest request,
            String idempotencyKey) {

        // =====================================================
        // 1. LOAD SHOW
        // =====================================================

        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        SeatReservationException.showNotFound(showId));

        // =====================================================
        // 2. VALIDATE SEAT COUNT
        // =====================================================

        if (request.getSeats().size()
                > appProperties.getMaxSeatsPerRequest()) {

            throw SeatReservationException.tooManySeats(
                    appProperties.getMaxSeatsPerRequest());
        }

        // =====================================================
        // 3. CHECK USER BOOKING LIMIT
        // =====================================================

        UserBookingLimit userLimit =
                checkAndIncrementUserLimit(
                        showId,
                        userId,
                        request.getSeats().size(),
                        show.getPerUserLimit());

        // =====================================================
        // 4. LOAD SEATS WITH PESSIMISTIC LOCK
        // =====================================================

        List<Seat> seats =
                seatRepository.findByShowIdAndSeatNumbersForUpdate(
                        showId,
                        request.getSeats());

        if (seats.size() != request.getSeats().size()) {
            throw SeatReservationException.seatNotFound(
                    "one or more requested seats");
        }

        // =====================================================
        // 5. VERIFY SEATS
        // =====================================================

        for (Seat seat : seats) {

            if (!seat.isAvailable()) {

                if (seat.isHeld() && seat.isExpired()) {
                    continue;
                }

                throw SeatReservationException.seatTaken(
                        seat.getSeatNumber());
            }
        }

        // =====================================================
        // 6. HOLD SEATS
        // =====================================================

        OffsetDateTime now = OffsetDateTime.now();

        OffsetDateTime holdExpiresAt =
                now.plus(appProperties.getHoldTtl());

        for (Seat seat : seats) {

            int updated =
                    seatRepository.updateSeatStatusWithVersion(
                            seat.getId(),
                            seat.getVersion(),
                            SeatStatus.AVAILABLE,
                            SeatStatus.HELD,
                            userId,
                            holdExpiresAt);

            if (updated == 0) {
                throw SeatReservationException.seatTaken(
                        seat.getSeatNumber());
            }
        }

        // =====================================================
        // 7. CREATE RESERVATION
        // =====================================================

        Long amountPaise =
                show.getPricePaise()
                        * request.getSeats().size();

        Reservation reservation =
                Reservation.builder()
                        .idempotencyKey(idempotencyKey)
                        .showId(showId)
                        .userId(userId)
                        .amountPaise(amountPaise)
                        .status(ReservationStatus.HELD)
                        .expiresAt(holdExpiresAt)
                        .build();

        for (Seat seat : seats) {

            ReservationSeat reservationSeat =
                    ReservationSeat.builder()
                            .reservation(reservation)
                            .seat(seat)
                            .build();

            reservation.getReservationSeats()
                    .add(reservationSeat);
        }

        Reservation savedReservation =
                reservationRepository.save(reservation);

        // =====================================================
        // 8. SAVE USER BOOKING LIMIT
        // =====================================================

        userBookingLimitRepository.save(userLimit);

        log.info(
                "Reservation held: id={}, user={}, seats={}, expiresAt={}",
                savedReservation.getReservationId(),
                userId,
                request.getSeats(),
                holdExpiresAt);

        // =====================================================
        // 9. BUILD RESPONSE
        // =====================================================

        return ReserveResponse.builder()
                .reservationId(
                        savedReservation.getReservationId())
                .showId(showId)
                .userId(userId)
                .seats(request.getSeats())
                .amountPaise(amountPaise)
                .status("HELD")
                .build();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConfirmResponse confirmInTransaction(
            UUID reservationId,
            Long userId) {

        // =====================================================
        // 1. LOAD RESERVATION WITH DB LOCK
        // =====================================================

        Reservation reservation =
                reservationRepository
                        .findByIdForUpdate(reservationId)
                        .orElseThrow(() ->
                                SeatReservationException
                                        .reservationNotFound(
                                                reservationId));

        // =====================================================
        // 2. VERIFY USER
        // =====================================================

        if (!reservation.getUserId().equals(userId)) {

            throw SeatReservationException
                    .reservationNotFound(reservationId);
        }

        // =====================================================
        // 3. VERIFY RESERVATION STATE
        // =====================================================

        if (reservation.getStatus()
                != ReservationStatus.HELD) {

            throw SeatReservationException.invalidReservationState(
                    "Reservation is not HELD");
        }

        // =====================================================
        // 4. VERIFY EXPIRY
        // =====================================================

        OffsetDateTime now =
                OffsetDateTime.now();

        if (reservation.getExpiresAt() == null
                || !reservation.getExpiresAt().isAfter(now)) {

            throw SeatReservationException
                    .reservationExpired(reservationId);
        }

        // =====================================================
        // 5. GET RESERVATION SEATS
        // =====================================================

        List<ReservationSeat> reservationSeats =
                reservation.getReservationSeats();

        // =====================================================
        // 6. CONFIRM SEATS
        // =====================================================

        for (ReservationSeat reservationSeat :
                reservationSeats) {

            Seat seat = reservationSeat.getSeat();

            if (seat.getStatus() != SeatStatus.HELD) {

                throw SeatReservationException.seatTaken(
                        seat.getSeatNumber());
            }

            if (!userId.equals(seat.getUserId())) {

                throw SeatReservationException.seatTaken(
                        seat.getSeatNumber());
            }

            int updated =
                    seatRepository.confirmSeat(
                            seat.getId(),
                            seat.getVersion(),
                            SeatStatus.HELD,
                            SeatStatus.CONFIRMED);

            if (updated != 1) {

                throw SeatReservationException.seatTaken(
                        seat.getSeatNumber());
            }
        }

        // =====================================================
        // 7. CONFIRM RESERVATION
        // =====================================================

        reservation.setStatus(
                ReservationStatus.CONFIRMED);

        reservation.setConfirmedAt(now);

        reservationRepository.save(reservation);

        // =====================================================
        // 8. UPDATE BOOKING COUNTERS
        // =====================================================

        int seatCount =
                reservation.getReservationSeats().size();

        // Existing counter-update logic here.

        // =====================================================
        // 9. BUILD RESPONSE
        // =====================================================

        return ConfirmResponse.builder()
                .reservationId(reservationId)
                .showId(reservation.getShowId())
                .userId(reservation.getUserId())
                .amountPaise(reservation.getAmountPaise())
                .status("CONFIRMED")
                .build();
    }

    private UserBookingLimit checkAndIncrementUserLimit(
            Long showId,
            Long userId,
            int requestedSeats,
            int perUserLimit) {

        UserBookingLimit userLimit =
                userBookingLimitRepository
                        .findByUserIdAndShowIdForUpdate(
                                userId,
                                showId)
                        .orElseGet(() ->
                                UserBookingLimit.builder()
                                        .userId(userId)
                                        .showId(showId)
                                        .ticketsHeld(0)
                                        .ticketsConfirmed(0)
                                        .build());

        int newTotal =
                userLimit.getTotalTickets()
                        + requestedSeats;

        if (newTotal > perUserLimit) {

            throw SeatReservationException
                    .userLimitExceeded(perUserLimit);
        }

        userLimit.setTicketsHeld(
                userLimit.getTicketsHeld()
                        + requestedSeats);

        return userLimit;
    }
}