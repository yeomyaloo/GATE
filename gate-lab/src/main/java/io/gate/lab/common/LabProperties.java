package io.gate.lab.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gate-lab 설정.
 *
 * @param ack          {@code local-only}여야 시작한다 (외부 공개 금지에 대한 명시적 동의)
 * @param jwtSecret    JWT 서명 키. 비어 있으면 시작할 때마다 무작위로 만든다 (32바이트 이상)
 * @param demoPassword 데모 계정 비밀번호. 비어 있으면 시작할 때마다 무작위로 만든다
 * @param inContainer  컨테이너 안에서 실행 중인지. 컨테이너에서는 0.0.0.0에 묶이되 호스트는 127.0.0.1에만 연다
 */
@ConfigurationProperties("gate.lab")
public record LabProperties(String ack, String jwtSecret, String demoPassword, boolean inContainer) {
}
