package io.gate.pkg.check;

import io.gate.common.finding.Finding;
import java.util.List;

/** 패키지 하나를 보고 결과를 낸다. 해당 사항이 없으면 빈 목록(= PASS)을 돌려준다. */
public interface Check {

    List<Finding> evaluate(CheckContext context);
}
