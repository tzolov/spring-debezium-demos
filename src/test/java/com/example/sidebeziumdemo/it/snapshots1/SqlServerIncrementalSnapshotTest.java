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
		// Note: change_lsn and commit_lsn are SQL Server specific metadata
		"debezium.properties.transforms.flatten.add.headers=op,change_lsn,commit_lsn",
		// "debezium.properties.transforms.flatten.add.fields=name,db,op,table",
		// "debezium.properties.transforms.flatten.add.headers=name,db,op,table",

		"debezium.properties.schema.history.internal=io.debezium.relational.history.MemorySchemaHistory",
		"debezium.properties.offset.storage=org.apache.kafka.connect.storage.MemoryOffsetBackingStore",

		"debezium.properties.key.converter.schemas.enable=false",
		"debezium.properties.value.converter.schemas.enable=false",

		"debezium.properties.topic.prefix=my-topic",
		"debezium.properties.name=my-connector",
		"debezium.properties.database.server.id=85744",

		// Sql Server specific configuration.
		"debezium.properties.connector.class=io.debezium.connector.sqlserver.SqlServerConnector",
		"debezium.properties.database.user=sa",
		"debezium.properties.database.password=MyFancyPassword123",
		"debezium.properties.database.hostname=localhost",
		"debezium.properties.database.names=testDB",
		"debezium.properties.database.encrypt=false",

		"debezium.properties.snapshot.mode=schema_only",
		"debezium.properties.signal.data.collection=testDB.dbo.dbz_signal",
		"debezium.properties.table.include.list=dbo.orders,dbo.customers,dbo.products,dbo.dbz_signal",

		// JdbcTemplate configuration
		"app.datasource.username=sa",
		"app.datasource.password=MyFancyPassword123",
		"app.datasource.type=com.zaxxer.hikari.HikariDataSource",
})
@DirtiesContext
public class SqlServerIncrementalSnapshotTest extends AbstractIncrementalSnapshotTest
		implements SqlServerTestContainer {

	@DynamicPropertySource
	static void dynamicProperties(DynamicPropertyRegistry registry) {
		registry.add("debezium.properties.database.port", () -> SqlServerTestContainer.mappedPort());
		registry.add("app.datasource.url",
				() -> String.format("jdbc:sqlserver://localhost:%d;encrypt=false;databaseName=%s",
						SqlServerTestContainer.mappedPort(), "testDB"));
	}

	protected void insertCustomer(String firstName, String lastName, String email) {
		jdbcTemplate.update("INSERT INTO customers(first_name,last_name,email) VALUES (?,?,?)", firstName, lastName,
				email);
	}

	protected void insertProduct(String name, String description, Float weight) {
		jdbcTemplate.update("INSERT INTO products VALUES (?,?,?)", name, description, weight);
	}

	protected void deleteProductByName(String name) {
		jdbcTemplate.update("DELETE FROM products WHERE name like ?", name);
	}

	protected void updateProductName(String oldName, String newName) {
		jdbcTemplate.update("UPDATE products set name=? WHERE name like ?", newName, oldName);
	}

	protected void startIncrementalSnapshotFor(String... dataCollections) {
		String names = Stream.of(dataCollections).map(name -> "\"testDB.dbo." + name + "\"")
				.collect(Collectors.joining(","));
		jdbcTemplate.update(
				"INSERT INTO testDB.dbo.dbz_signal (id, type, data) VALUES ('ad-hoc-1', 'execute-snapshot',"
						+ "'{\"data-collections\": [" + names + "],\"type\":\"incremental\"}')");
	}

	protected void stopIncrementalSnapshotFor(String... dataCollections) {
		String names = Stream.of(dataCollections).map(name -> "\"testDB.dbo." + name + "\"")
				.collect(Collectors.joining(","));
		jdbcTemplate.update(
				"INSERT INTO testDB.dbo.dbz_signal (id, type, data) VALUES ('ad-hoc-1', 'stop-snapshot',"
						+ "'{\"data-collections\": [" + names + "],\"type\":\"incremental\"}')");
	}

	protected String customers() {
		return "my-topic.testDB.dbo.customers";
	}

	protected String products() {
		return "my-topic.testDB.dbo.products";
	}

	protected String orders() {
		return "my-topic.testDB.dbo.orders";
	}

	protected String dbzSignal() {
		return "my-topic.testDB.dbo.dbz_signal";
	}

	protected String getLsnHeaderName() {
		return "__commit_lsn";
	}

}
