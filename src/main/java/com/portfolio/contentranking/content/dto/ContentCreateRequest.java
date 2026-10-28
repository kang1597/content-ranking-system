package com.portfolio.contentranking.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContentCreateRequest(

        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 200, message = "제목은 200자를 넘을 수 없습니다.")
        String title,

        @NotBlank(message = "본문은 필수입니다.")
        @Size(max = 20000, message = "본문은 20000자를 넘을 수 없습니다.")
        String body
) {
}