package com.example.sidebeziumdemo.eosapp;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.zaxxer.hikari.HikariDataSource;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.spi.OffsetCommitPolicy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.log.LogAccessor;
import org.springframework.integration.debezium.dsl.Debezium;
import org.springframework.integration.debezium.dsl.DebeziumMessageProducerSpec;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;

@SpringBootApplication(exclude = { MongoAutoConfiguration.class, DataSourceAutoConfiguration.class })
public class PostgresCdcDemoApplication implements CommandLineRunner {

	static final LogAccessor logger = new LogAccessor(PostgresCdcDemoApplication.class);

	CyclicBarrier barrier = new CyclicBarrier(2);

	Set<Integer> valueSet = new ConcurrentSkipListSet<>();

	BloomFilter<Long> bloomFilter = BloomFilter.create(Funnels.longFunnel(), 1000000, 0.01);

	ObjectMapper mapper = new ObjectMapper();

	AtomicLong duplicationCount = new AtomicLong(0);

	AtomicLong falsePositiveCount = new AtomicLong(0);

	AtomicLong falseNegativeCount = new AtomicLong(0);

	@Autowired
	private JdbcTemplate jdbcTemplate;

	public static void main(String[] args) {
		new SpringApplicationBuilder(PostgresCdcDemoApplication.class)
				.web(WebApplicationType.NONE)
				.run("--spring.config.location=classpath:/application-postgres-eos.properties");
	}

	@Override
	public void run(String... args) throws Exception {
		Executors.newSingleThreadExecutor().submit(() -> {
			try {
				barrier.await();
			}
			catch (InterruptedException | BrokenBarrierException e) {
				e.printStackTrace();
			}

			for (int i = 1; i < 600000; i++) {
				insertRow(i);

				if ((i % 20000) == 0) {
					pgTerminateBackend();
				}
			}
		});
	}

	private void insertRow(int i) {
		if ((i % 1000) == 0)
			System.out.print("*");

		jdbcTemplate.update(
				String.format("INSERT INTO public.eos_test(val) VALUES (%s) ", i));
	}

	private void pgTerminateBackend() {
		Executors.newSingleThreadExecutor().submit(() -> {
			System.out.println("\nMIN: " + Collections.min(valueSet));
			System.out.println("MAX: " + Collections.max(valueSet));
			System.out.println("Duplication Count: " + duplicationCount.getAndSet(0));
			System.out.println("False Positive: " + falsePositiveCount.getAndSet(0));
			System.out.println("False Negative: " + falseNegativeCount.getAndSet(0));

			List<Map<String, Object>> result = this.jdbcTemplate.queryForList(
					"SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
							"WHERE pid <> pg_backend_pid() AND datname = 'postgres' AND query like 'START_REPLICATION SLOT %';");
			System.out
					.println("\n pg_terminate_backend result: " + result.size());
		});
	}

	private int getPayloadValue(Message<?> m) {
		try {
			Map<?, ?> values = mapper.readValue((byte[]) m.getPayload(), Map.class);
			return (Integer) values.get("val");
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private long getLsn(Message<?> m) {
		try {
			Map<?, ?> values = mapper.readValue((byte[]) m.getHeaders().get("__lsn"), Map.class);
			return Long.valueOf("" + values.get("payload"));
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Bean
	public IntegrationFlow streamFlowFromBuilder(DebeziumEngine.Builder<ChangeEvent<byte[], byte[]>> builder) {

		// builder = builder.using(OffsetCommitPolicy.always())

		builder = builder.using(new DebeziumEngine.ConnectorCallback() {
			public void taskStarted() {
				try {
					System.out.println("BARRIER AWAIT!");
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

					Long messageLsn = getLsn(m);
					Integer messageValue = getPayloadValue(m);

					if (this.bloomFilter.mightContain(messageLsn)) {
						if (valueSet.contains(messageValue)) {
							duplicationCount.incrementAndGet();
						}
						else {
							falsePositiveCount.incrementAndGet();
						}
					}
					else if (valueSet.contains(messageValue)) {
						falseNegativeCount.incrementAndGet();
					}

					valueSet.add(messageValue);
					bloomFilter.put(messageLsn);
				})
				.get();
	}

	//////////////////
	@Bean
	public JdbcTemplate myJdbcTemplate(DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	@Bean
	@Primary
	@ConfigurationProperties("app.datasource")
	public DataSourceProperties dataSourceProperties() {
		return new DataSourceProperties();
	}

	@Bean
	public HikariDataSource dataSource(DataSourceProperties dataSourceProperties) {
		return dataSourceProperties.initializeDataSourceBuilder()
				.type(HikariDataSource.class)
				.build();
	}

}
