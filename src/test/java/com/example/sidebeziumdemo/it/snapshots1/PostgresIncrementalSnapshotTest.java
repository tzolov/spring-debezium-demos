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

package com.example.sidebeziumdemo.it.snapshots1;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * @author Christian Tzolov
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = AbstractIncrementalSnapshotTest.StreamTestConfiguration.class, properties = {
		// Common configuration. Common for all DBs.
		"debezium.properties.transforms=flatten",
		"debezium.properties.transforms.flatten.type=io.debezium.transforms.ExtractNewRecordState",
		"debezium.properties.transforms.flatten.drop.tombstones=true",
		"debezium.properties.transforms.flatten.delete.handling.mode=rewrite",
		// Note: 'lsn' is Postgres specific metadata
		"debezium.properties.transforms.flatten.add.headers=op,lsn",
		// "debezium.properties.transforms.flatten.add.fields=name,db,op,table",
		// "debezium.properties.transforms.flatten.add.headers=name,db,op,table",

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

		"debezium.properties.snapshot.mode=never",

		"debezium.properties.signal.data.collection=inventory.dbz_signal",
		"debezium.properties.table.include.list=inventory.orders,inventory.customers,inventory.products,inventory.dbz_signal",

		// JdbcTemplate configuration
		"app.datasource.username=postgres",
		"app.datasource.password=postgres",
		"app.datasource.type=com.zaxxer.hikari.HikariDataSource",
})
@DirtiesContext
public class PostgresIncrementalSnapshotTest extends AbstractIncrementalSnapshotTest implements PostgresTestContainer {

	@DynamicPropertySource
	static void dynamicProperties(DynamicPropertyRegistry registry) {
		registry.add("debezium.properties.database.port", () -> PostgresTestContainer.mappedPort());
		registry.add("app.datasource.url",
				() -> String.format("jdbc:postgresql://localhost:%s/postgres", PostgresTestContainer.mappedPort()));
	}

	protected void insertCustomer(String firstName, String lastName, String email) {
		jdbcTemplate.update("INSERT INTO inventory.customers VALUES (default,?,?,?)", firstName, lastName, email);
	}

	protected void insertProduct(String name, String description, Float weight) {
		jdbcTemplate.update("INSERT INTO inventory.products VALUES (default,?,?,?)", name, description, weight);
	}

	protected void deleteProductByName(String name) {
		jdbcTemplate.update("DELETE FROM inventory.products WHERE name like ?", name);
	}

	protected void updateProductName(String oldName, String newName) {
		jdbcTemplate.update("UPDATE inventory.products set name=? WHERE name like ?", newName, oldName);
	}

	protected void startIncrementalSnapshotFor(String... dataCollections) {
		String names = Stream.of(dataCollections).map(name -> "\"inventory." + name + "\"")
				.collect(Collectors.joining(","));
		jdbcTemplate.update(
				"INSERT INTO inventory.dbz_signal (id, type, data) VALUES ('ad-hoc-1', 'execute-snapshot',"
						+ "'{\"data-collections\": [" + names + "],\"type\":\"incremental\"}')");
	}

	protected void stopIncrementalSnapshotFor(String... dataCollections) {
		String names = Stream.of(dataCollections).map(name -> "\"inventory." + name + "\"")
				.collect(Collectors.joining(","));
		jdbcTemplate.update(
				"INSERT INTO inventory.dbz_signal (id, type, data) VALUES ('ad-hoc-1', 'stop-snapshot',"
						+ "'{\"data-collections\": [" + names + "],\"type\":\"incremental\"}')");
	}

	protected String customers() {
		return "my-topic.inventory.customers";
	}

	protected String products() {
		return "my-topic.inventory.products";
	}

	protected String orders() {
		return "my-topic.inventory.orders";
	}

	protected String dbzSignal() {
		return "my-topic.inventory.dbz_signal";
	}

	protected String getLsnHeaderName() {
		return "__lsn";
	}

}
