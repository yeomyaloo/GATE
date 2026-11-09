package io.gate.lab.common;

import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 스키마와 가짜 데이터를 만든다. 데모 계정 비밀번호는 코드·문서에 남기지 않도록 시작할 때마다 무작위로 만들고
 * 콘솔에만 출력한다 ({@code GATE_LAB_DEMO_PASSWORD}로 지정 가능).
 */
@Component
@DependsOn("labSafetyGuard") // 안전장치 확인이 먼저 끝나야 데이터를 만든다
public class LabDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(LabDataInitializer.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final LabProperties props;

    public LabDataInitializer(DataSource dataSource, JdbcTemplate jdbc, PasswordEncoder encoder,
            LabProperties props) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.props = props;
    }

    @PostConstruct
    void init() {
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(dataSource);

        boolean generated = props.demoPassword() == null || props.demoPassword().isBlank();
        String password = generated ? randomPassword() : props.demoPassword();
        String hash = encoder.encode(password);
        jdbc.update("INSERT INTO users (username, display_name, email, password_hash, role) VALUES (?, ?, ?, ?, ?)",
                "alice", "Alice Kim", "alice@example.invalid", hash, "USER");
        jdbc.update("INSERT INTO users (username, display_name, email, password_hash, role) VALUES (?, ?, ?, ?, ?)",
                "bob", "Bob Lee", "bob@example.invalid", hash, "USER");
        jdbc.update("INSERT INTO users (username, display_name, email, password_hash, role) VALUES (?, ?, ?, ?, ?)",
                "admin", "Lab Admin", "admin@example.invalid", encoder.encode(randomPassword()), "ADMIN");

        String[][] products = {
            {"Mechanical Keyboard", "89000", "false"},
            {"USB-C Hub", "39000", "false"},
            {"Noise Cancelling Headphones", "199000", "false"},
            {"Laptop Stand", "45000", "false"},
            {"Internal Prototype (unreleased)", "0", "true"},
        };
        for (String[] p : products) {
            jdbc.update("INSERT INTO products (name, price, hidden) VALUES (?, ?, ?)", p[0], Integer.parseInt(p[1]),
                    Boolean.parseBoolean(p[2]));
        }

        long alice = jdbc.queryForObject("SELECT id FROM users WHERE username = 'alice'", Long.class);
        long bob = jdbc.queryForObject("SELECT id FROM users WHERE username = 'bob'", Long.class);
        insertOrder(alice, 1, 1, "gift wrap please", "Seoul, Example-ro 1");
        insertOrder(bob, 3, 1, "leave at the door", "Busan, Sample-gil 22");
        insertOrder(bob, 2, 2, "", "Busan, Sample-gil 22");

        if (generated) {
            log.warn("gate-lab demo accounts (local practice only): alice / bob, password: {}", password);
        } else {
            log.warn("gate-lab demo accounts: alice / bob, password from GATE_LAB_DEMO_PASSWORD");
        }
    }

    private void insertOrder(long userId, long productId, int quantity, String note, String address) {
        jdbc.update("INSERT INTO orders (user_id, product_id, quantity, note, shipping_address) VALUES (?, ?, ?, ?, ?)",
                userId, productId, quantity, note, address);
    }

    static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
