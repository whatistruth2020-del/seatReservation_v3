package com.paytm.seatreservation.service;

import com.paytm.seatreservation.config.AppProperties;
import com.paytm.seatreservation.config.ReservationMetrics;
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
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final UserBookingLimitRepository userBookingLimitRepository;
    private final RedisLockService redisLockService;
    private final IdempotencyService idempotencyService;
    private final ReservationTransactionService    reservationTransactionService;
    private final AppProperties appProperties;
    private final ReservationMetrics metrics;
    

    public ReserveResponse reserveSeats(
            Long showId,
            Long userId,
            ReserveRequest request,
            String idempotencyKey) {

        Timer.Sample reservationTimer =
                metrics.startReservationTimer();

        try {

            // =====================================================
            // 1. CHECK COMPLETED IDEMPOTENCY RESPONSE
            // =====================================================

            Optional<ReserveResponse> cached =
                    idempotencyService.getCachedResponse(
                            "reserve",
                            idempotencyKey,
                            ReserveResponse.class);

            if (cached.isPresent()) {

                metrics.incrementDeclinedIdempotentReplay();

                log.info(
                        "Idempotent replay for reserve key: {}",
                        idempotencyKey);

                return cached.get();
            }

            // =====================================================
            // 2. MARK REQUEST AS PENDING
            // =====================================================

            if (!idempotencyService.tryMarkPending(
                    "reserve",
                    idempotencyKey,
                    appProperties.getIdempotencyTtl())) {

                Optional<ReserveResponse> pendingCached =
                        waitForPending(
                                "reserve",
                                idempotencyKey,
                                ReserveResponse.class);

                if (pendingCached.isPresent()) {

                    metrics.incrementDeclinedIdempotentReplay();

                    return pendingCached.get();
                }

                throw SeatReservationException.internalError(
                        "Reservation request is already being processed");
            }

            RedisLockService.AcquiredLocks lockResult = null;

            try {

                // =================================================
                // 3. CHEAP VALIDATION
                // =================================================

                if (request.getSeats() == null
                        || request.getSeats().isEmpty()) {

                    throw SeatReservationException
                            .seatNotFound("no seats requested");
                }

                if (request.getSeats().size()
                        > appProperties.getMaxSeatsPerRequest()) {

                    throw SeatReservationException.tooManySeats(
                            appProperties.getMaxSeatsPerRequest());
                }

                // =================================================
                // 4. REDIS SEAT LOCK
                // =================================================

                Timer.Sample lockTimer =
                        metrics.startLockAcquireTimer();

                try {

                    lockResult =
                            redisLockService.acquireLocks(
                                    showId,
                                    request.getSeats(),
                                    appProperties.getHoldTtl());

                } finally {

                    metrics.stopLockAcquireTimer(lockTimer);
                }

                if (lockResult == null) {

                    metrics.incrementDeclinedSeatTaken();

                    throw SeatReservationException.seatTaken(
                            "one or more requested seats");
                }

                // =================================================
                // 5. DATABASE TRANSACTION
                // =================================================

                Timer.Sample dbTimer =
                        metrics.startDbTransactionTimer();

                ReserveResponse response;

                try {

                    response =
                            reservationTransactionService
                                    .reserveInTransaction(
                                            showId,
                                            userId,
                                            request,
                                            idempotencyKey);

                } finally {

                    metrics.stopDbTransactionTimer(dbTimer);
                }

                // =================================================
                // 6. SUCCESSFUL RESERVATION
                // =================================================

                idempotencyService.cacheResponse(
                        "reserve",
                        idempotencyKey,
                        response,
                        appProperties.getIdempotencyTtl());

                idempotencyService.removePending(
                        "reserve",
                        idempotencyKey);

                metrics.incrementHeld();

                updateSeatsAvailableMetric(showId);

                return response;

            } finally {

                // =================================================
                // 7. ALWAYS RELEASE REDIS LOCK
                // =================================================

                if (lockResult != null) {

                    redisLockService.releaseLocks(
                            lockResult.getKeys(),
                            lockResult.getLockValue());
                }
            }

        } catch (SeatReservationException e) {

            idempotencyService.removePending(
                    "reserve",
                    idempotencyKey);

            throw e;

        } catch (Exception e) {

            idempotencyService.removePending(
                    "reserve",
                    idempotencyKey);

            metrics.incrementServerError();

            log.error(
                    "Unexpected error during reservation",
                    e);

            throw SeatReservationException.internalError(
                    "Reservation failed: " + e.getMessage());

        } finally {

            // Entire HTTP reservation workflow.
            metrics.stopReservationTimer(reservationTimer);
        }
    }

   



    private void updateSeatsAvailableMetric(Long showId) {
        long available = seatRepository.countByShowIdAndStatus(showId, SeatStatus.AVAILABLE);
        metrics.updateSeatsAvailable(showId, available);
    }


    public ConfirmResponse confirmReservation(
            UUID reservationId,
            Long userId,
            String idempotencyKey) {

        Timer.Sample reservationTimer =
                metrics.startReservationTimer();

        try {

            // =====================================================
            // 1. CHECK CONFIRM IDEMPOTENCY CACHE
            // =====================================================

            Optional<ConfirmResponse> cached =
                    idempotencyService.getCachedResponse(
                            "confirm",
                            idempotencyKey,
                            ConfirmResponse.class);

            if (cached.isPresent()) {

                metrics.incrementDeclinedIdempotentReplay();

                log.info(
                        "Idempotent replay for confirm key: {}",
                        idempotencyKey);

                return cached.get();
            }

            // =====================================================
            // 2. MARK REQUEST AS PENDING
            // =====================================================

            if (!idempotencyService.tryMarkPending(
                    "confirm",
                    idempotencyKey,
                    appProperties.getIdempotencyTtl())) {

                Optional<ConfirmResponse> pendingCached =
                        waitForPending(
                                "confirm",
                                idempotencyKey,
                                ConfirmResponse.class);

                if (pendingCached.isPresent()) {

                    metrics.incrementDeclinedIdempotentReplay();

                    return pendingCached.get();
                }

                throw SeatReservationException.internalError(
                        "Confirm request is already being processed");
            }

            RedisLockService.AcquiredLocks lockResult = null;

            try {

                // =================================================
                // 3. GET SEAT NUMBERS FOR REDIS LOCK
                // =================================================
                
                /*
                 * We need the seat numbers before entering the
                 * database transaction so Redis can be the gatekeeper.
                 *
                 * IMPORTANT:
                 * Do not use findByIdForUpdate() here.
                 * That would start doing DB locking before Redis.
                 *
                 * We need a lightweight lookup here.
                 */

                List<String> seatNumbers =
                        reservationSeatRepository
                                .findSeatNumbersByReservationId(
                                        reservationId);

                if (seatNumbers == null
                        || seatNumbers.isEmpty()) {

                    throw SeatReservationException
                            .reservationNotFound(reservationId);
                }
                Long showId =
                reservationSeatRepository.findShowIdByReservationId(reservationId);

                if (showId == null) {

                    throw SeatReservationException
                            .reservationNotFound(reservationId);
        }
                // =================================================
                // 4. ACQUIRE REDIS SEAT LOCK
                // =================================================

                Timer.Sample lockTimer =
                        metrics.startLockAcquireTimer();

                try {

                    lockResult =
                            redisLockService.acquireLocks(
                            		showId,
                                    seatNumbers,
                                    appProperties.getHoldTtl());

                } finally {

                    metrics.stopLockAcquireTimer(lockTimer);
                }

                if (lockResult == null) {

                    metrics.incrementDeclinedSeatTaken();

                    throw SeatReservationException.seatTaken(
                            "one or more seats are locked");
                }

                // =================================================
                // 5. DATABASE TRANSACTION
                // =================================================

                Timer.Sample dbTimer =
                        metrics.startDbTransactionTimer();

                ConfirmResponse response;

                try {

                    response =
                            reservationTransactionService
                                    .confirmInTransaction(
                                            reservationId,
                                            userId);

                } finally {

                    metrics.stopDbTransactionTimer(dbTimer);
                }

                // =================================================
                // 6. CACHE SUCCESSFUL RESPONSE
                // =================================================

                idempotencyService.cacheResponse(
                        "confirm",
                        idempotencyKey,
                        response,
                        appProperties.getIdempotencyTtl());

                // =================================================
                // 7. REMOVE PENDING
                // =================================================

                idempotencyService.removePending(
                        "confirm",
                        idempotencyKey);

                // =================================================
                // 8. CONFIRMATION METRICS
                // =================================================

                metrics.incrementConfirmed();

                updateSeatsAvailableMetric(
                        response.getShowId());

                return response;

            } finally {

                // =================================================
                // 9. ALWAYS RELEASE REDIS LOCK
                // =================================================

                if (lockResult != null) {

                    redisLockService.releaseLocks(
                            lockResult.getKeys(),
                            lockResult.getLockValue());
                }
            }

        } catch (SeatReservationException e) {

            idempotencyService.removePending(
                    "confirm",
                    idempotencyKey);

            throw e;

        } catch (Exception e) {

            idempotencyService.removePending(
                    "confirm",
                    idempotencyKey);

            metrics.incrementServerError();

            log.error(
                    "Unexpected error during confirmation",
                    e);

            throw SeatReservationException.internalError(
                    "Confirmation failed: " + e.getMessage());

        } finally {

            // Entire confirmation API latency
            metrics.stopReservationTimer(
                    reservationTimer);
        }
    }
    
    
    private <T> Optional<T> waitForPending(
            String operation,
            String idempotencyKey,
            Class<T> responseType) {

        for (int i = 0; i < 10; i++) {

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }

            Optional<T> cached =
                    idempotencyService.getCachedResponse(
                            operation,
                            idempotencyKey,
                            responseType
                    );

            if (cached.isPresent()) {
                return cached;
            }
        }

        return Optional.empty();
    }
}