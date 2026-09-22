package com.openbank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "JWT_SECRET=" + GatewayTestJwt.SECRET)
class GatewayServiceApplicationTest {

    @Test
    void contextLoads() {
    }
}