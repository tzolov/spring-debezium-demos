/*
 * Copyright 2023-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.sidebeziumdemo.it.eos;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.zaxxer.hikari.HikariDataSource;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.log.LogAccessor;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.debezium.dsl.Debezium;
import org.springframework.integration.debezium.dsl.DebeziumMessageProducerSpec;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Christian Tzolov
 */
@DirtiesContext
public class PostgresEosTest implements PostgresEosTestContainer {

	static final LogAccessor logger = new LogAccessor(PostgresEosTest.class);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(PostgresEosTest.StreamTestConfiguration.class)
			.withPropertyValues(
					// Common configuration. Common for all DBs.
					"debezium.properties.transforms=flatten",
					"debezium.properties.transforms.flatten.type=io.debezium.transforms.ExtractNewRecordState",
					"debezium.properties.transforms.flatten.drop.tombstones=true",
					"debezium.properties.transforms.flatten.delete.handling.mode=rewrite",
					// Note: 'lsn' is Postgres specific metadata
					"debezium.properties.transforms.flatten.add.headers=op,lsn",

					"debezium.properties.schema.history.internal=io.debezium.relational.history.MemorySchemaHistory",
					"debezium.properties.offset.storage=org.apache.kafka.connect.storage.MemoryOffsetBackingStore",

					"debezium.properties.key.converter.schemas.enable=false",
					"debezium.properties.value.converter.schemas.enable=false",

					"debezium.properties.topic.prefix=my-topic",
					"debezium.properties.name=my-connector",
					"debezium.properties.database.server.id=85744",

					// Postgres specific configuration.
					"debezium.properties.connector.class=io.debezium.connector.postgresql.PostgresConnector",
					"debezium.properties.database.user=postgres",
					"debezium.properties.database.password=postgres",
					"debezium.properties.slot.name=debezium",
					"debezium.properties.database.dbname=postgres",
					"debezium.properties.database.hostname=localhost",
					"debezium.properties.database.port=" + PostgresEosTestContainer.mappedPort(),

					"debezium.properties.snapshot.mode=never");

	static CyclicBarrier barrier = new CyclicBarrier(2);

	AtomicBoolean dataGenerationStopped = new AtomicBoolean(false);

	@BeforeAll
	public static void beforeAll() {
		Awaitility.setDefaultTimeout(10, TimeUnit.MINUTES);
	}

	@Test
	public void testOffsetCommitPolicyPERIODIC() {

		contextRunner.withPropertyValues("debezium.properties.offset.flush.interval.ms=10000")
				.run(context -> {

					runDataGenerationWithEmulatedFailures(context, 1, 30000);

					StreamTestConfiguration config = context.getBean(StreamTestConfiguration.class);

					logger.info("[TOTAL PERIODIC] Duplications:" + config.totalDuplications.get() + ", FP: "
							+ config.totalFalsePositive.get() + ", FN: " + config.totalFalseNegative.get());

					assertThat(config.totalDuplications.getAndSet(0)).isGreaterThan(0)
							.as("The PERIODIC commit policy presumes a decent amount of duplications");
					assertThat(config.totalFalsePositive.getAndSet(0)).isGreaterThan(0)
							.as("The Bloom Filter is expected to yeld ~0.2 false positive results");
					assertThat(config.totalFalseNegative.getAndSet(0)).isEqualTo(0)
							.as("The Bloom Filter should never return false negative matches");
				});

	}

	@Test
	public void testOffsetCommitPolicyALWAYS() {

		contextRunner.withPropertyValues("debezium.offsetCommitPolicy=ALWAYS")
				.run(context -> {

					runDataGenerationWithEmulatedFailures(context, 30001, 30000);

					StreamTestConfiguration config = context.getBean(StreamTestConfiguration.class);

					logger.info("[TOTAL ALWAYS] Duplications:" + config.totalDuplications.get() + ", FP: "
							+ config.totalFalsePositive.get() + ", FN: " + config.totalFalseNegative.get());

					assertThat(config.totalDuplications.getAndSet(0)).isEqualTo(0)
							.as("The ALWAYS commit policy is expected to prevent duplications");
					assertThat(config.totalFalsePositive.getAndSet(0)).isGreaterThan(0)
							.as("The Bloom Filter is expected to yeld ~0.2 false positive results");
					assertThat(config.totalFalseNegative.getAndSet(0)).isEqualTo(0)
							.as("The Bloom Filter should never return false negative matches");
				});

	}

	private void runDataGenerationWithEmulatedFailures(ApplicationContext context, int startIndex, int size) {

		JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);

		StreamTestConfiguration config = context.getBean(StreamTestConfiguration.class);

		dataGenerationStopped.set(false);

		Executors.newSingleThreadExecutor().submit(() -> {

			try {
				// wait until the Debezium connector is up and running.
				barrier.await();
			}
			catch (InterruptedException | BrokenBarrierException e) {
				logger.error(e, "Failed to reach data generation start phase.");
			}

			for (int i = startIndex; i < startIndex + size; i++) {
				// Continuously insert new data entries.
				config.insertRow(jdbcTemplate, i);

				// On every 1000 new inserts emulate a connector breakdown.
				if ((i % 5000) == 0) {
					config.pgTerminateBackend(jdbcTemplate);
				}
			}

			dataGenerationStopped.set(true);
		});

		Awaitility.await().until(() -> dataGenerationStopped.get());
	}

	@SpringBootConfiguration
	@EnableIntegration
	@EnableAutoConfiguration(exclude = { MongoAutoConfiguration.class })
	public static class StreamTestConfiguration {

		Set<Integer> valueSet = new ConcurrentSkipListSet<>();

		BloomFilter<Long> bloomFilter = BloomFilter.create(Funnels.longFunnel(), 30000, 0.05);

		ObjectMapper mapper = new ObjectMapper();

		AtomicLong duplications = new AtomicLong(0);
		AtomicLong falsePositive = new AtomicLong(0);
		AtomicLong falseNegative = new AtomicLong(0);

		AtomicLong totalDuplications = new AtomicLong(0);
		AtomicLong totalFalsePositive = new AtomicLong(0);
		AtomicLong totalFalseNegative = new AtomicLong(0);

		@Bean
		public IntegrationFlow streamFlowFromBuilder(DebeziumEngine.Builder<ChangeEvent<byte[], byte[]>> builder) {

			builder = builder.using(new DebeziumEngine.ConnectorCallback() {
				@Override
				public void taskStarted() {
					try {
						barrier.await();
					}
					catch (InterruptedException | BrokenBarrierException e) {
						e.printStackTrace();
					}
				}
			});

			DebeziumMessageProducerSpec dsl = Debezium.inboundChannelAdapter(builder)
					.headerNames("*")
					.contentType("application/json")
					.enableBatch(false)
					.enableEmptyPayload(true);

			return IntegrationFlow.from(dsl)
					.handle(m -> {

						Long messageLsn = getMessageLsn(m);
						Integer messageValue = getMessageValue(m);

						if (this.bloomFilter.mightContain(messageLsn)) {
							if (valueSet.contains(messageValue)) {
								duplications.incrementAndGet();
							}
							else {
								falsePositive.incrementAndGet();
							}
						}
						else if (valueSet.contains(messageValue)) {
							falseNegative.incrementAndGet();
						}

						valueSet.add(messageValue);
						bloomFilter.put(messageLsn);
					})
					.get();
		}

		private void insertRow(JdbcTemplate jdbcTemplate, int i) {
			jdbcTemplate.update(String.format("INSERT INTO public.eos_test(val) VALUES (%s) ", i));
		}

		private void pgTerminateBackend(JdbcTemplate jdbcTemplate) {
			Executors.newSingleThreadExecutor().submit(() -> {

				totalDuplications.addAndGet(duplications.get());
				totalFalseNegative.addAndGet(falseNegative.get());
				totalFalsePositive.addAndGet(falsePositive.get());

				logger.info("[LOCAL] Dup.:" + duplications.getAndSet(0) + ", FP: "
						+ falsePositive.getAndSet(0) + ", FN: " + falseNegative.getAndSet(0));

				List<Map<String, Object>> result = jdbcTemplate.queryForList(
						"SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
								"WHERE pid <> pg_backend_pid() AND datname = 'postgres' AND query like 'START_REPLICATION SLOT %';");
				logger.info("pg_terminate_backend result: " + result.size());
			});
		}

		private int getMessageValue(Message<?> m) {
			try {
				Map<?, ?> values = mapper.readValue((byte[]) m.getPayload(), Map.class);
				return (Integer) values.get("val");
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		private long getMessageLsn(Message<?> m) {
			try {
				Map<?, ?> values = mapper.readValue((byte[]) m.getHeaders().get("__lsn"), Map.class);
				return Long.valueOf("" + values.get("payload"));
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Bean
		public JdbcTemplate myJdbcTemplate(DataSource dataSource) {
			return new JdbcTemplate(dataSource);
		}

		@Bean
		public HikariDataSource hikariDataSource() {
			return DataSourceBuilder.create()
					.driverClassName("org.postgresql.Driver")
					.url(String.format("jdbc:postgresql://localhost:%s/postgres",
							PostgresEosTestContainer.mappedPort()))
					.username("postgres")
					.password("postgres")
					.type(HikariDataSource.class)
					.build();
		}
	}

}
