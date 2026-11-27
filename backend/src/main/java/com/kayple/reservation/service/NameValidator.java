package com.kayple.reservation.service;

import com.kayple.reservation.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class NameValidator {

    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "이름을 입력해 주세요.");
        }
        String name = raw.trim();
        if (name.length() < 2 || name.length() > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "이름은 2자 이상 50자 이하로 입력해 주세요.");
        }
        if (!name.matches("^[가-힣a-zA-Z\\s]+$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "이름은 한글 또는 영문만 입력해 주세요.");
        }
        return name;
    }
}
