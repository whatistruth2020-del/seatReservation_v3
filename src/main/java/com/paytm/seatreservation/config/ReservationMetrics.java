package com.paytm.seatreservation.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ReservationMetrics {

    private final MeterRegistry registry;
    private final Counter confirmedCounter;
    private final Counter heldCounter;
    private final Counter declinedSeatTakenCounter;
    private final Counter declinedUserLimitCounter;
    private final Counter declinedIdempotentReplayCounter;
    private final Counter declinedOtherCounter;
    private final Counter serverErrorCounter;
    private final Timer reservationLatencyTimer;
    private final Timer lockAcquireLatencyTimer;
    private final Timer dbTransactionLatencyTimer;
    private final ConcurrentHashMap<Long, AtomicLong> seatsAvailableGauges = new ConcurrentHashMap<>();
    private final ReentrantLock gaugeLock = new ReentrantLock();

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.confirmedCounter = Counter.builder("reservation.confirmed.total")
                .description("Total confirmed reservations")
                .register(registry);

        this.heldCounter = Counter.builder("reservation.confirmed.total")
                .description("Total Held reservations")
                .register(registry);

        this.declinedSeatTakenCounter = Counter.builder("reservation.declined.seat_taken.total")
                .description("Total reservations declined due to seat already taken")
                .register(registry);

        this.declinedUserLimitCounter = Counter.builder("reservation.declined.user_limit.total")
                .description("Total reservations declined due to per-user limit exceeded")
                .register(registry);

        this.declinedIdempotentReplayCounter = Counter.builder("reservation.declined.idempotent_replay.total")
                .description("Total idempotent replay requests")
                .register(registry);

        this.declinedOtherCounter = Counter.builder("reservation.declined.other.total")
                .description("Total reservations declined for other reasons")
                .register(registry);

        this.serverErrorCounter = Counter.builder("reservation.errors.server.total")
                .description("Total server errors during reservation")
                .register(registry);

        this.reservationLatencyTimer = Timer.builder("reservation.latency")
                .description("Reservation request latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.lockAcquireLatencyTimer = Timer.builder("reservation.lock.acquire.latency")
                .description("Redis lock acquisition latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.dbTransactionLatencyTimer = Timer.builder("reservation.db.transaction.latency")
                .description("Database transaction latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void incrementConfirmed() {
        confirmedCounter.increment();
    }

    public void incrementDeclinedSeatTaken() {
        declinedSeatTakenCounter.increment();
    }

    public void incrementDeclinedUserLimit() {
        declinedUserLimitCounter.increment();
    }

    public void incrementDeclinedIdempotentReplay() {
        declinedIdempotentReplayCounter.increment();
    }

    public void incrementDeclinedOther() {
        declinedOtherCounter.increment();
    }

    public void incrementServerError() {
        serverErrorCounter.increment();
    }

    public Timer.Sample startReservationTimer() {
        return Timer.start(registry);
    }

    public Timer.Sample startLockAcquireTimer() {
        return Timer.start(registry);
    }

    public Timer.Sample startDbTransactionTimer() {
        return Timer.start(registry);
    }

    public void stopReservationTimer(Timer.Sample sample) {
        sample.stop(reservationLatencyTimer);
    }

    public void stopLockAcquireTimer(Timer.Sample sample) {
        sample.stop(lockAcquireLatencyTimer);
    }

    public void stopDbTransactionTimer(Timer.Sample sample) {
        sample.stop(dbTransactionLatencyTimer);
    }

    public void updateSeatsAvailable(Long showId, long available) {
        gaugeLock.lock();
        try {
            seatsAvailableGauges.computeIfAbsent(showId, id -> {
                AtomicLong gauge = new AtomicLong(available);
                Gauge.builder("seats.available", gauge, AtomicLong::get)
                        .tag("show_id", id.toString())
                        .description("Number of available seats for show")
                        .register(registry);
                return gauge;
            }).set(available);
        } finally {
            gaugeLock.unlock();
        }
    }

	public void incrementHeld() {
		// TODO Auto-generated method stub
		heldCounter.increment();
		
	}
}