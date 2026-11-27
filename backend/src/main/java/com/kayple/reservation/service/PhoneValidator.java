package com.kayple.reservation.service;

import com.kayple.reservation.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PhoneValidator {

    public String normalize(String raw) {
        if (raw == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE", "연락처를 입력해 주세요.");
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (!digits.matches("^01[016789][0-9]{7,8}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE", "올바른 휴대전화 번호를 입력해 주세요.");
        }
        return digits;
    }
}
