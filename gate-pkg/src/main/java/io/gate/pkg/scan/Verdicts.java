package io.gate.pkg.scan;

import io.gate.common.finding.Decision;
import java.util.Collection;

/** 판정 합치기: 가장 강한 것이 이긴다 (BLOCK > WARN > PASS). 결과가 없으면 PASS. */
public final class Verdicts {

    private Verdicts() {
    }

    public static Decision strongest(Collection<Decision> decisions) {
        if (decisions.contains(Decision.BLOCK)) {
            return Decision.BLOCK;
        }
        if (decisions.contains(Decision.WARN)) {
            return Decision.WARN;
        }
        return Decision.PASS;
    }
}
