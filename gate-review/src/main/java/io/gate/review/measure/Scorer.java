package io.gate.review.measure;

import io.gate.review.Cwe;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 정답지 대비 채점 (설계안 3장).
 * <ul>
 *   <li>검출(TP): 정답 취약점의 파일·줄 범위와 겹치고 CWE 종류가 같은 지적이 하나라도 있으면 그 취약점은 검출</li>
 *   <li>미탐(FN): 어떤 지적도 맞히지 못한 정답 취약점</li>
 *   <li>오탐(FP): 어떤 정답과도 맞지 않는 지적 (위치가 맞아도 종류가 다르면 오탐). 고친 버전에서는 모든 지적이 오탐</li>
 *   <li>중복: 이미 검출된 취약점을 다시 가리킨 지적. 오탐으로 세지 않고 따로 센다</li>
 * </ul>
 */
public final class Scorer {

    private Scorer() {
    }

    /**
     * @param detected        검출한 정답 ID
     * @param missed          놓친 정답 ID
     * @param falsePositives  오탐 지적
     * @param fpOnCleanFiles  오탐 중 "깨끗해야 할 파일"에 대한 것
     * @param duplicates      같은 정답을 다시 가리킨 지적 수
     * @param matched         정답을 맞힌 지적 (중복 포함, 원자료 확인용)
     */
    public record Score(List<String> detected, List<String> missed, List<Flag> falsePositives, int fpOnCleanFiles,
            int duplicates, int total, List<Flag> matched) {
    }

    /**
     * @param expected 이 버전에 있어야 할 정답 (고친 버전이면 빈 목록)
     * @param flags    원래 경로로 되돌린 지적
     */
    public static Score score(List<GroundTruth.Vulnerability> expected, List<String> cleanFiles, List<Flag> flags) {
        Set<String> detected = new LinkedHashSet<>();
        List<Flag> fps = new ArrayList<>();
        List<Flag> matched = new ArrayList<>();
        int duplicates = 0;
        for (Flag f : flags) {
            GroundTruth.Vulnerability hit = expected.stream().filter(v -> matches(v, f)).findFirst().orElse(null);
            if (hit == null) {
                fps.add(f);
            } else {
                matched.add(f);
                if (!detected.add(hit.id())) {
                    duplicates++;
                }
            }
        }
        List<String> missed = expected.stream().map(GroundTruth.Vulnerability::id).filter(id -> !detected.contains(id))
                .toList();
        int onClean = (int) fps.stream().filter(f -> cleanFiles.contains(f.path())).count();
        return new Score(List.copyOf(detected), missed, fps, onClean, duplicates, flags.size(), matched);
    }

    static boolean matches(GroundTruth.Vulnerability v, Flag f) {
        return v.file().equals(f.path()) && f.startLine() <= v.endLine() && f.endLine() >= v.startLine()
                && f.cwe() != null && Cwe.sameFamily(v.cwe(), f.cwe());
    }
}
