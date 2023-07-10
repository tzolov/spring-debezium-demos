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

package com.example.sidebeziumdemo.miscellaneous;

import javax.sql.DataSource;

import com.example.sidebeziumdemo.PrintUtils;
import com.example.sidebeziumdemo.it.snapshots1.MySqlTestContainer;
import com.zaxxer.hikari.HikariDataSource;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.integration.annotation.BridgeFrom;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.debezium.inbound.DebeziumMessageProducer;
import org.springframework.integration.debezium.support.DefaultDebeziumHeaderMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Christian Tzolov
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
		"debezium.properties.transforms=unwrap",
		"debezium.properties.transforms.unwrap.type=io.debezium.transforms.ExtractNewRecordState",
		"debezium.properties.transforms.unwrap.drop.tombstones=true",
		"debezium.properties.transforms.unwrap.delete.handling.mode=rewrite",
		// "debezium.properties.transforms.unwrap.add.fields=name,db,op,table",
		"debezium.properties.transforms.unwrap.add.headers=name,db,op,table",

		"debezium.properties.schema.history.internal=io.debezium.relational.history.MemorySchemaHistory",
		"debezium.properties.offset.storage=org.apache.kafka.connect.storage.MemoryOffsetBackingStore",

		// Drop schema from the message payload.
		"debezium.properties.key.converter.schemas.enable=false",
		"debezium.properties.value.converter.schemas.enable=false",

		"debezium.properties.topic.prefix=my-topic",
		"debezium.properties.name=my-connector",
		"debezium.properties.database.server.id=85744",

		"debezium.properties.connector.class=io.debezium.connector.sqlserver.SqlServerConnector",
		"debezium.properties.database.user=sa",
		"debezium.properties.database.password=MyFancyPassword123",
		"debezium.properties.database.hostname=localhost",
		"debezium.properties.database.names=testDB",

		"debezium.properties.table.include.list=dbo.orders,dbo.customers,dbo.products",
})
@DirtiesContext
public class SimpleTest implements MySqlTestContainer {

	@DynamicPropertySource
	static void mysqlDbProperties(DynamicPropertyRegistry registry) {
		registry.add("debezium.properties.database.port", () -> MySqlTestContainer.mappedPort());
	}

	@Autowired
	@Qualifier("queueChannel")
	private QueueChannel queueChannel;

	@Test
	void streamMode() {
		boolean foundDebeziumHeaders = false;
		for (int i = 0; i < 20; i++) {
			Message<?> message = this.queueChannel.receive(20_000);
			assertThat(message).as("Index: " + i).isNotNull();

			System.out.println(PrintUtils.headersToString(message.getHeaders()));
			System.out.println(PrintUtils.prettyJson((byte[]) message.getPayload()));

			if (message.getHeaders().size() > 5) {
				assertThat(message.getHeaders()).containsKeys("__name", "__db", "__op", "__table");
				foundDebeziumHeaders = true;
			}
		}
		assertThat(foundDebeziumHeaders).isTrue();
	}

	@Configuration
	@EnableIntegration
	@EnableAutoConfiguration(exclude = { MongoAutoConfiguration.class, DataSourceAutoConfiguration.class})
	public static class StreamTestConfiguration {

		@Bean
		public MessageProducer debeziumMessageProducer(
				DebeziumEngine.Builder<ChangeEvent<byte[], byte[]>> debeziumEngineBuilder) {

			DebeziumMessageProducer debeziumMessageProducer = new DebeziumMessageProducer(debeziumEngineBuilder);
			debeziumMessageProducer.setOutputChannel(debeziumInputChannel());
			return debeziumMessageProducer;
		}

		@Bean
		public MessageChannel debeziumInputChannel() {
			return new DirectChannel();
		}

		@Bean
		@BridgeFrom("debeziumInputChannel")
		public MessageChannel queueChannel() {
			return new QueueChannel();
		}

	}

}
