package io.gate.lab.assistant;

import io.gate.prompt.model.AssistantModel;
import io.gate.prompt.model.ScriptedModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 주문 도우미(5단계)의 공통 부분. 모델은 가짜 모델(입력 속 지시를 무엇이든 따르는 최악의 모델)이다.
 * 두 버전이 같은 모델을 쓰므로, 결과 차이는 모델이 아니라 출력 관문에서 나온다.
 */
@Configuration
public class AssistantConfig {

    /** 요청 본문 (세 기능 공통). 글자 수는 컨트롤러에서 제한한다. */
    public record Question(String question) {
    }

    public record ReviewRequest(Long productId) {
    }

    public record ActionRequest(String request) {
    }

    public static final int MAX_INPUT = 500;

    @Bean
    AssistantModel assistantModel() {
        return new ScriptedModel();
    }

    public static String limited(String s) {
        if (s == null || s.isBlank() || s.length() > MAX_INPUT) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "text is required (max " + MAX_INPUT + ")");
        }
        return s;
    }
}
