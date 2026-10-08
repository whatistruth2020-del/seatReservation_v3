package com.paytm.seatreservation.repository;


import com.paytm.seatreservation.entity.ReservationSeat;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {

	@Query("""
		    SELECT rs.seat.seatNumber
		    FROM ReservationSeat rs
		    WHERE rs.reservation.reservationId = :reservationId
		""")
		List<String> findSeatNumbersByReservationId(
		        UUID reservationId);
	
	@Query("""
		    SELECT r.showId
		    FROM Reservation r
		    WHERE r.reservationId = :reservationId
		""")
		Long findShowIdByReservationId(
		        UUID reservationId);
}