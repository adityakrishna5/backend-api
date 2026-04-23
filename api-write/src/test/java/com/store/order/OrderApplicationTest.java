package com.store.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import static org.mockito.ArgumentMatchers.any;

@DisplayName("OrderApplication Main Tests")
class OrderApplicationTest {

    @Test
    @DisplayName("main: delegates to SpringApplication.run")
    void main_delegatesToSpringApplicationRun() {
        try (MockedStatic<SpringApplication> mocked = Mockito.mockStatic(SpringApplication.class)) {
            mocked.when(() -> SpringApplication.run(any(Class.class), any(String[].class)))
                  .thenReturn(Mockito.mock(ConfigurableApplicationContext.class));

            OrderApplication.main(new String[]{});

            mocked.verify(() -> SpringApplication.run(OrderApplication.class, new String[]{}));
        }
    }
}
