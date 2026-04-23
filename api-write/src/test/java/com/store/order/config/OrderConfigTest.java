package com.store.order.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Config Unit Tests (api-write)")
class OrderConfigTest {

    private static final String BOOTSTRAP = "localhost:9092";

    // ── OpenApiConfig ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("OpenApiConfig.openAPI: returns non-null bean with correct title")
    void openApiConfig_openAPI_returnsBean() {
        OpenAPI openAPI = new OpenApiConfig().openAPI();
        assertThat(openAPI).isNotNull();
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("Order Processing API");
    }

    @Test
    @DisplayName("OpenApiConfig.openAPI: contains bearerAuth security scheme")
    void openApiConfig_openAPI_hasBearerAuthScheme() {
        OpenAPI openAPI = new OpenApiConfig().openAPI();
        assertThat(openAPI.getComponents().getSecuritySchemes()).containsKey("bearerAuth");
    }

    // ── DataSourceConfig ──────────────────────────────────────────────────────

    @Test
    @DisplayName("DataSourceConfig: can be instantiated")
    void dataSourceConfig_canBeInstantiated() {
        assertThat(new DataSourceConfig()).isNotNull();
    }

    // ── KafkaConfig ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("KafkaConfig.producerFactory: creates DefaultKafkaProducerFactory")
    void kafkaConfig_producerFactory_createsFactory() {
        KafkaConfig config = new KafkaConfig();
        ProducerFactory<String, Object> factory = config.producerFactory(BOOTSTRAP);
        assertThat(factory).isInstanceOf(DefaultKafkaProducerFactory.class);
    }

    @Test
    @DisplayName("KafkaConfig.kafkaTemplate: creates KafkaTemplate wrapping the factory")
    void kafkaConfig_kafkaTemplate_createsTemplate() {
        KafkaConfig config = new KafkaConfig();
        ProducerFactory<String, Object> factory = config.producerFactory(BOOTSTRAP);
        KafkaTemplate<String, Object> template = config.kafkaTemplate(factory);
        assertThat(template).isNotNull();
    }

    @Test
    @DisplayName("KafkaConfig.consumerFactory: creates DefaultKafkaConsumerFactory")
    void kafkaConfig_consumerFactory_createsFactory() {
        KafkaConfig config = new KafkaConfig();
        ConsumerFactory<String, Object> factory = config.consumerFactory(BOOTSTRAP);
        assertThat(factory).isInstanceOf(DefaultKafkaConsumerFactory.class);
    }

    @Test
    @DisplayName("KafkaConfig.kafkaListenerContainerFactory: creates single-record factory")
    void kafkaConfig_listenerContainerFactory_createsSingleRecordFactory() {
        KafkaConfig config = new KafkaConfig();
        ConsumerFactory<String, Object> cf = config.consumerFactory(BOOTSTRAP);
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                config.kafkaListenerContainerFactory(cf);
        assertThat(factory).isNotNull();
        assertThat(factory.isBatchListener()).isFalse();
    }

    @Test
    @DisplayName("KafkaConfig.batchKafkaListenerContainerFactory: creates batch factory")
    void kafkaConfig_batchListenerContainerFactory_createsBatchFactory() {
        KafkaConfig config = new KafkaConfig();
        ConsumerFactory<String, Object> cf = config.consumerFactory(BOOTSTRAP);
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                config.batchKafkaListenerContainerFactory(cf);
        assertThat(factory).isNotNull();
        assertThat(factory.isBatchListener()).isTrue();
    }
}
