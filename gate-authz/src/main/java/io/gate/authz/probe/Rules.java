package io.gate.authz.probe;

/** gate-authz 규칙 ID (설계안 2.1). */
public final class Rules {

    public static final String BOLA_READ = "AUTHZ-BOLA-READ";
    public static final String BOLA_WRITE = "AUTHZ-BOLA-WRITE";
    public static final String BOLA_DELETE = "AUTHZ-BOLA-DELETE";
    public static final String NOAUTH = "AUTHZ-NOAUTH";
    public static final String INCONCLUSIVE = "AUTHZ-INCONCLUSIVE";

    private Rules() {
    }
}
