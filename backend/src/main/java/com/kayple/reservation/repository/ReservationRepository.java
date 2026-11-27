package com.kayple.reservation.repository;

import com.kayple.reservation.domain.Reservation;
import com.kayple.reservation.domain.ReservationStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class ReservationRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Reservation> ROW_MAPPER = (rs, rowNum) -> {
        Reservation r = new Reservation();
        r.setId(rs.getLong("id"));
        r.setCustomerName(rs.getString("customer_name"));
        r.setPhone(rs.getString("phone"));
        r.setReservationDate(rs.getDate("reservation_date").toLocalDate());
        r.setReservationHour(rs.getInt("reservation_hour"));
        r.setStatus(ReservationStatus.valueOf(rs.getString("status")));
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) {
            r.setCreatedAt(created.toLocalDateTime());
        }
        Timestamp cancelled = rs.getTimestamp("cancelled_at");
        if (cancelled != null) {
            r.setCancelledAt(cancelled.toLocalDateTime());
        }
        return r;
    };

    public ReservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Reservation insert(Reservation reservation) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO reservations
                    (customer_name, phone, reservation_date, reservation_hour, status)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, reservation.getCustomerName());
            ps.setString(2, reservation.getPhone());
            ps.setDate(3, Date.valueOf(reservation.getReservationDate()));
            ps.setInt(4, reservation.getReservationHour());
            ps.setString(5, reservation.getStatus().name());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            reservation.setId(key.longValue());
        }
        return reservation;
    }

    public List<Integer> findConfirmedHours(LocalDate date) {
        return jdbcTemplate.queryForList(
                """
                SELECT reservation_hour FROM reservations
                WHERE reservation_date = ? AND status = 'CONFIRMED'
                """,
                Integer.class,
                Date.valueOf(date));
    }

    public List<Reservation> findByPhone(String phone) {
        return jdbcTemplate.query(
                """
                SELECT * FROM reservations
                WHERE phone = ?
                ORDER BY reservation_date DESC, reservation_hour DESC
                """,
                ROW_MAPPER,
                phone);
    }

    public Optional<Reservation> findById(Long id) {
        try {
            Reservation reservation = jdbcTemplate.queryForObject(
                    "SELECT * FROM reservations WHERE id = ?",
                    ROW_MAPPER,
                    id);
            return Optional.ofNullable(reservation);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int cancelIfConfirmed(Long id, String phone) {
        return jdbcTemplate.update(
                """
                UPDATE reservations
                SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP
                WHERE id = ? AND phone = ? AND status = 'CONFIRMED'
                """,
                id,
                phone);
    }

    public List<Reservation> findByDate(LocalDate date) {
        return jdbcTemplate.query(
                """
                SELECT * FROM reservations
                WHERE reservation_date = ?
                ORDER BY reservation_hour ASC, created_at ASC
                """,
                ROW_MAPPER,
                Date.valueOf(date));
    }
}
