package com.example.sidebeziumdemo.postgres;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.log.LogAccessor;
import org.springframework.integration.debezium.dsl.Debezium;
import org.springframework.integration.debezium.dsl.DebeziumMessageProducerSpec;
import org.springframework.integration.debezium.support.DebeziumHeaders;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.util.StringUtils;

@SpringBootApplication(exclude = { MongoAutoConfiguration.class, DataSourceAutoConfiguration.class })
public class PostgresCdcDemoApplication {

	static final LogAccessor logger = new LogAccessor(PostgresCdcDemoApplication.class);

	public static void main(String[] args) {
		new SpringApplicationBuilder(PostgresCdcDemoApplication.class)
				.web(WebApplicationType.NONE)
				// .properties("spring.config.location=classpath:/application.properties")
				.run("--spring.config.location=classpath:/application-postgres.properties");
	}

	@Bean
	public IntegrationFlow streamFlowFromBuilder(DebeziumEngine.Builder<ChangeEvent<byte[], byte[]>> builder) {

		DebeziumMessageProducerSpec dsl = Debezium.inboundChannelAdapter(builder)
				.headerNames("*")
				.contentType("application/json")
				.enableBatch(false)
				.enableEmptyPayload(true);

		return IntegrationFlow.from(dsl)
				.handle(m -> {
					Object key = m.getHeaders().containsKey(DebeziumHeaders.KEY)
							? new String((byte[]) m.getHeaders().get(DebeziumHeaders.KEY))
							: "null";
					String op = new String((byte[]) m.getHeaders().get("__op"));
					Object destination = m.getHeaders().get(DebeziumHeaders.DESTINATION);
					// logger.info("KEY: " + key + ", DESTINATION: " + destination + ", OP: " + op);

					if (!"my-topic.inventory.dbz_signal".equals(destination)) {

						String c = "!";
						if (StringUtils.hasText(op)) {
							if (op.contains("\"r\"")) {
								c = "r";
							}
							else if (op.contains("\"c\"")) {
								c = "c";
								System.out.println(destination);
							}
							else if (op.contains("\"u\"")) {
								c = "u";
							}
							else if (op.contains("\"d\"")) {
								c = "d";
							}
							else {
								//c = "#";
								c = op;
							}

						}
						System.out.print(c);
					}

					// headerKeys.add(m.getHeaders().keySet());
					// payloads.add(new String((byte[]) m.getPayload()));
					// latch.countDown();
				})
				.get();
	}

}
