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

package com.example.sidebeziumdemo.postgres;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * @author Christian Tzolov
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
		// JdbcTemplate configuration
		"app.datasource.username=postgres",
		"app.datasource.password=postgres",
		"app.datasource.type=com.zaxxer.hikari.HikariDataSource",
		"app.datasource.url=jdbc:postgresql://localhost:5432/postgres"
})
@DirtiesContext
public class DataGenerator {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	public void beforeAll() {
		for (int i = 0; i < 100000; i++) {
			jdbcTemplate.update(
					"INSERT INTO inventory.customers(first_name,last_name,email) " +
							String.format("VALUES('%s', '%s', '%s@spring2.org')", "first_" + i, "last_" + i,
									"name_" + i));

			if ((i % 10000) == 0) {
				try {
					Thread.sleep(1000);
				}
				catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}

	@Test
	void streamMode() {

		try {
			Thread.sleep(1000 * 1000);
		}
		catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	@Configuration
	@EnableIntegration
	@EnableAutoConfiguration(exclude = { MongoAutoConfiguration.class })
	public static class StreamTestConfiguration {

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

}
