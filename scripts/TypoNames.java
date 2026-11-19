import io.gate.pkg.check.TypoDetector;
import io.gate.pkg.registry.Ecosystem;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 탐지율 측정 보조: 표준 입력의 패키지 이름을 gate-pkg의 철자 흉내 탐지기(TypoDetector)에만 넣는다.
 * 네트워크·OSV·레지스트리 조회 없음 → "OSV에 올라가기 전, 이름만으로 잡는가"를 잰다.
 *
 *   java -cp gate-pkg/build/libs/gate-pkg.jar scripts/TypoNames.java npm < names.txt
 * 출력: 이름 TAB (대상 TAB 기법 | -)
 */
public class TypoNames {
    public static void main(String[] args) throws Exception {
        Ecosystem eco = args[0].equalsIgnoreCase("npm") ? Ecosystem.NPM : Ecosystem.PYPI;
        TypoDetector detector = TypoDetector.load(eco);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (String line; (line = in.readLine()) != null; ) {
            String name = line.strip();
            if (name.isEmpty()) {
                continue;
            }
            String result = detector.find(name).map(m -> m.target() + "\t" + m.technique()).orElse("-");
            out.append(name).append('\t').append(result).append('\n');
        }
        System.out.print(out);
    }
}
