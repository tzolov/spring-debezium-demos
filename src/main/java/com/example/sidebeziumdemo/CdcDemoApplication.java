package com.example.sidebeziumdemo;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.pipeline.notification.Notification;
import io.debezium.pipeline.signal.SignalRecord;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.integration.debezium.dsl.Debezium;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.metadata.MetadataStore;
import org.springframework.integration.metadata.SimpleMetadataStore;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@SpringBootApplication(exclude = { MongoAutoConfiguration.class })
@EnableScheduling
public class CdcDemoApplication implements ApplicationContextAware {

	private ApplicationEventPublisher publisher;

	public static void main(String[] args) {
		new SpringApplicationBuilder(CdcDemoApplication.class)
				.web(WebApplicationType.NONE)
				.run("--spring.config.location=classpath:/application-boza.properties");
	}

	@Bean
	public IntegrationFlow streamFlowFromBuilder(DebeziumEngine.Builder<ChangeEvent<byte[], byte[]>> builder) {

		return IntegrationFlow.from(Debezium.inboundChannelAdapter(builder)
				.enableEmptyPayload(true))
				.handle(message -> {
					if (PrintUtils.toString(message.getPayload()).contains("\"r\"")) {
						System.out.println(PrintUtils.headersToString(message.getHeaders()));
						System.out.println(PrintUtils.prettyJson((byte[]) message.getPayload()));
					}
				}).get();
	}

	// Custom Offset Store based on SI MetadataStore.
	@Bean
	public MetadataStore simpleMetadataStore() {
		return new SimpleMetadataStore();
	}

	// Register listener for Debezium's 'Notification' events.
	@EventListener
	public void applicationEventListener(PayloadApplicationEvent<Notification> applicationEvent) {
		System.out.println("APPLICATION EVENT: " + applicationEvent.getPayload());
	}

	// Send Incremental Snapshot as Spring Application Event
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.publisher = applicationContext;
	}

	@Scheduled(fixedDelay = 2 * 60 * 1000, initialDelay = 10 * 1000)
	public void scheduleFixedDelayTask() {
		SignalRecord signal = new SignalRecord("'ad-hoc-666", "execute-snapshot",
				"{\"data-collections\": [\"testDB.dbo.orders\", \"testDB.dbo.customers\", \"testDB.dbo.products\"],\"type\":\"incremental\"}",
				null);
		this.publisher.publishEvent(signal);

		System.out.println("Incremental Snapshot Task - " + signal.toString());
	}

}
