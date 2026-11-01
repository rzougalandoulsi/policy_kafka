package com.itland.governance.infrastructure.kafka;

import com.itland.governance.configuration.GovernanceProperties;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.RecordNameStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/** Native Confluent clients with the platform conventions (Avro, RecordNameStrategy, idempotent producer, manual commits). */
@Configuration
public class KafkaClientFactory {

  @Bean(destroyMethod = "close")
  public KafkaProducer<String, Object> commandProducer(GovernanceProperties properties, MeterRegistry registry) {
    GovernanceProperties.Kafka k = properties.kafka();
    Map<String, Object> props = common(k);
    props.put(ProducerConfig.CLIENT_ID_CONFIG, k.producerClientId());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 15_000);
    props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
    props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
    KafkaProducer<String, Object> producer = new KafkaProducer<>(props);
    new KafkaClientMetrics(producer).bindTo(registry);
    return producer;
  }

  /** Not a bean: the consumer is owned by the consumer thread. */
  public static KafkaConsumer<String, Object> newConsumer(GovernanceProperties.Kafka k) {
    Map<String, Object> props = common(k);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, k.consumerGroup());
    props.put(ConsumerConfig.CLIENT_ID_CONFIG, k.consumerGroup() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());
    props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); // the Orchestrator produces transactionally (EOS v2)
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
    props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
    return new KafkaConsumer<>(props);
  }

  static Map<String, Object> common(GovernanceProperties.Kafka k) {
    Map<String, Object> props = new HashMap<>();
    props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, k.bootstrapServers());
    props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, k.schemaRegistryUrl());
    props.put(AbstractKafkaSchemaSerDeConfig.VALUE_SUBJECT_NAME_STRATEGY, RecordNameStrategy.class.getName());
    props.put(AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, true);
    if (!k.securityProtocol().isBlank()) {
      props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, k.securityProtocol());
      props.put(SaslConfigs.SASL_MECHANISM, k.saslMechanism());
      props.put(SaslConfigs.SASL_JAAS_CONFIG, k.saslJaasConfig());
    }
    if (!k.schemaRegistryAuth().isBlank()) {
      props.put(AbstractKafkaSchemaSerDeConfig.BASIC_AUTH_CREDENTIALS_SOURCE, "USER_INFO");
      props.put(AbstractKafkaSchemaSerDeConfig.USER_INFO_CONFIG, k.schemaRegistryAuth());
    }
    return props;
  }
}
