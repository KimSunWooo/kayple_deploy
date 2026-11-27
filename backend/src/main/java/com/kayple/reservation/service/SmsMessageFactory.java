package com.kayple.reservation.service;

import com.kayple.reservation.domain.Reservation;
import com.kayple.reservation.domain.SmsEventType;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.Locale;

@Component
public class SmsMessageFactory {

    public String create(Reservation reservation, SmsEventType eventType) {
        String datePart = formatDate(reservation);
        String timePart = String.format("%02d:00", reservation.getReservationHour());
        if (eventType == SmsEventType.BOOKED) {
            return "[케이플 상담] " + datePart + " " + timePart + " 상담이 예약되었습니다.";
        }
        return "[케이플 상담] " + datePart + " " + timePart + " 상담 예약이 취소되었습니다.";
    }

    private String formatDate(Reservation reservation) {
        DayOfWeek day = reservation.getReservationDate().getDayOfWeek();
        String dayName = day.getDisplayName(TextStyle.SHORT, Locale.KOREAN);
        return String.format("%d월 %d일(%s)",
                reservation.getReservationDate().getMonthValue(),
                reservation.getReservationDate().getDayOfMonth(),
                dayName);
    }
}
