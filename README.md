# Incremental Snapshots

The Change-DataCapture (CDC) captures changed rows from a database’s transaction log and delivers them downstream with low latency.
In order to solve the data synchronization problem one also needs to replicate the full state of the database.

But the transaction logs typically do not contain the full history of changes.
The `initial consistent snapshot` can accomplish this by scanning the database tables in a single, read transaction operation.
Depends on the database size this could take hours to complete.
Furthermore until it completes no new database changes can be streamed.

There are use cases that require high availability of the transaction log events so that databases stay as closely in-sync as possible.
To facilitate such use-cases, Debezium includes an option to perform `ad hoc snapshots` called `incremental snapshots`.

Unlike the `initial snapshot`, the `incremental snapshot` captures tables in chunks, rather than all at once using a [watermarking]((https://arxiv.org/pdf/2010.12597v1.pdf)) method to track the progress of the snapshot.
Furthermore the incremental snapshot captures the initial state of the tables, without interrupting the streaming of transaction log events!

You can initiate an incremental snapshot at any time.
Also the process can be paused/interrupted at any time and later resumed from the point at which it was stopped.

| Snapshot features              | Initial | Incremental |
| ------------- |:-------------:| -----:|
| Can be triggered at any time | NO  | YES |
| Can be paused and resumed    | NO  | YES |
| Log event processing does not stall | NO | YES  |
| Preserves the order of history  | YES | YES  |
| Does not use locks  | NO | YES  |

Debezium implements a special signal protocol for [starting](https://debezium.io/documentation/reference/configuration/signalling.html#debezium-signaling-ad-hoc-snapshots), [stopping](https://debezium.io/documentation/reference/configuration/signalling.html#debezium-signaling-stop-ad-hoc-snapshots), [pausing](https://debezium.io/documentation/reference/configuration/signalling.html#debezium-signaling-pause-incremental-snapshots) or [resuming](https://debezium.io/documentation/reference/configuration/signalling.html#debezium-signaling-resume-incremental-snapshots) the incremental snapshots.

To enable the incremental snapshot one need to create a `signaling table` with fixed [data structure](https://debezium.io/documentation/reference/2.3/configuration/signalling.html#debezium-signaling-data-collection-structure) and then use one of the provided Signal Channels to start/stop/pause/resume the process.
Use the `debezium.properties.signal.enabled.channels` property to select the desired signal channel.
For example here we would enable the source and the SpringSignalChannelReader channel readers.

```
debezium.properties.signal.enabled.channels=source,SpringSignalChannelReader
```

And the use an `Application` event to trigger the snapshot:

```java
private ApplicationEventPublisher publisher = ...;

SignalRecord signal = new SignalRecord("'ad-hoc-666", "execute-snapshot",
  "{\"data-collections\": [\"testDB.dbo.orders\", \"testDB.dbo.customers\", \"testDB.dbo.products\"],\"type\":\"incremental\"}",
  null);
this.publisher.publishEvent(signal);
```

Check the `CdcDemoApplication.java` for a sample implementation that leverages the `SpringSignalChannelReader` channel.
Note that the the incremental snapshot still requires the presence and use of the signaling table and therefore the `source` channel as well.

The [SNAPSHOT1](https://github.com/tzolov/spring-debezium-demos/tree/main/src/test/java/com/example/sidebeziumdemo/it/snapshots1) and [SNAPSHOT2](https://github.com/tzolov/spring-debezium-demos/tree/main/src/test/java/com/example/sidebeziumdemo/it/snapshots2) integration test leverage the `source` channel and use JdbcTemplate to write signals into the signal table.

The exact SQL statement implemented by the [stopIncrementalSnapshotFor()](https://github.com/tzolov/spring-debezium-demos/blob/main/src/test/java/com/example/sidebeziumdemo/it/snapshots2/AbstractIncrementalSnapshotTests.java#L241) method varies amongst the different database.

### References

- [Debezium Incremental Snapshots](https://debezium.io/documentation/reference/configuration/signalling.html#debezium-signaling-incremental-snapshots)
- [(blog) Incremental Snapshots in Debezium](https://debezium.io/blog/2021/10/07/incremental-snapshots/)
- [(paper) DBLog: A Watermark Based Change-Data-Capture Framework](https://arxiv.org/pdf/2010.12597v1.pdf)

# Exactly-Once Delivery

> By default Debezium provides the `at-least-once` event delivery semantics. This means Debezium guarantees every single change will be delivered and there is no missing or skipped change event. However, in case of failures, restarts or DB connection drops, the same event can be delivered more than once.

> The `exactly-once` delivery (or semantic) provides stronger guarantee - every single message will be delivered and at the same time there won’t be any duplicates, every single message will be delivered exactly once.

What are the option to ensure the `exactly-once semantics` (EOS) for the records produced by Debezium?

For a fully fledged Debezium deployments, with a dedicated Kafka Connect cluster, one can leverage the exactly-once delivery support already provided by `Kafka Connect`.
You can find more about this approach [Here](https://debezium.io/blog/2023/06/22/towards-exactly-once-delivery/).

For the [Embedded Debezium Engine](https://debezium.io/documentation/reference/development/engine.html) applications, such as the [Spring Boot Debezium integration](https://github.com/spring-cloud/stream-applications/tree/main/functions/common/debezium-autoconfigure), the only option to ensure exactly-once delivery is to facilitate the users to implement their own deduplication system!

You can find here couple of samples and test that illustrate how the deduplication can be implemented.

In general any de-duplication implementation would require (1) an unique Transaction ID; and (2) an efficient caching mechanism for it.

The [PostgresCdcDemoApplication.java](https://github.com/tzolov/spring-debezium-demos/blob/main/src/test/java/com/example/sidebeziumdemo/eosapp/PostgresCdcDemoApplication.java) sample
uses the Postgres' [Long Serial Number (LSN)](https://www.postgresql.org/docs/current/protocol-replication.html) as a CDC transaction ID.
Furthermore the `debezium.properties.transforms.flatten.add.headers=lsn` is used to assign the lsn to the message header.

NOTE: the `lsn` is Postgres specific. For MySQL one can opt for `pos` and for SQLServer for `change_lsn` and `commit_lsn` instead.
For other connectors follow the Debizium connector documentations.

Next the sample uses [Bloom Filters](https://www.baeldung.com/cs/bloom-filter) to test if an LSN is already received.
The Bloom Filters is very an efficient, low footprint probabilistic data structure used to test whether an element is a member of a set.
It guaranties zero False Negative answers.
That means that if an LSN has never been received the match test will always return false.
Though the Bloom Filters can produce False Positive answers, it can be configured so that this number is very low.
In addition to the Bloom Filters we implement a simple Java Set<LSN> to filter out the False Positive cases.

One can play with the Bloom Filter implementations. Here we opted for Guave Bloom Filters, but the [eosapp/bloom](https://github.com/tzolov/spring-debezium-demos/tree/main/src/test/java/com/example/sidebeziumdemo/eosapp/bloom) folder contains two additional experimental implementations.
Also you can experiment with different LSN caching mechanism instead of simple `Set` and implement range or time expiration strategies to improve the performance.

The [PostgresEosTest.java](https://github.com/tzolov/spring-debezium-demos/blob/main/src/test/java/com/example/sidebeziumdemo/it/eos/PostgresEosTest.java) integration test, implements an end-to-end test with different `debezium.offsetCommitPolicy`.
For example one can observe that in case of `debezium.offsetCommitPolicy=ALWAYS` duplications almost never observed!
This comes to a hit to the throughput as it required storing the CDC offset on every transaction.


Another approach to handle duplications is the SI [idempotent-receiver](https://docs.spring.io/spring-integration/docs/current/reference/html/messaging-endpoints.html#idempotent-receiver).
The idempotent receiver uses MessageSelector to extract the duplication check ID and is backed by a MetadataStore to keep the processed ids.
The [PostgresEos2Test.java](https://github.com/tzolov/spring-debezium-demos/blob/main/src/test/java/com/example/sidebeziumdemo/it/eos2/PostgresEos2Test.java) test explores this approach.



# Signaling and Notifications

The [Debezium signaling](https://debezium.io/documentation/reference/2.3/configuration/signalling.html) mechanism provides a way to modify the behavior of a connector, or to trigger a one-time action, such as initiating an ad hoc snapshot of a table.

The [Debezium notifications](https://debezium.io/documentation/reference/configuration/notification.html) provide a mechanism to obtain status information about the connector. Notifications can be sent to the configured channels.

The Spring [debezium-signals](https://github.com/tzolov/debezium-signals) project implements Spring Boot integrations for the signaling and notifications:

- `SpringNotificationChannel` - Debezium notification integration, that wraps the `io.debezium.pipeline.notification.Notification` signals into Spring Application Events.

- `SpringSignalChannelReader` - Debezium channel integration, that allows wrapping and sending the `io.debezium.pipeline.signal.SignalRecord` signals as Spring Application Events.

To use the `debezium-signals` you need to add the following dependency:

```xml
<!-- Enable Debezium Builder auto-configuration -->
<dependency>
 <groupId>org.springframework.cloud.fn</groupId>
 <artifactId>debezium-autoconfigure</artifactId>
 <version>${debezium.builder.version}</version>
</dependency>

<!-- Enable Spring Debezium Channel and Notificaiton integration -->
<dependency>
 <groupId>org.spring.boot.extension.autoconfigure</groupId>
 <artifactId>debezium-signals</artifactId>
 <version>0.0.2-SNAPSHOT</version>
</dependency>
```

and set the notification and signal channel properties:

```properties
debezium.properties.notification.enabled.channels=SpringNotificationChannel
debezium.properties.signal.enabled.channels=source,SpringSignalChannelReader
```

Then use the standard Spring events to monitor and control the embedded Debezium connectors:

```java
@SpringBootApplication(exclude = { MongoAutoConfiguration.class })
public class CdcDemoApplication implements ApplicationContextAware {

 private ApplicationEventPublisher publisher;

 @Override
 public void setApplicationContext(ApplicationContext applicationContext) {
  this.publisher = applicationContext;
 }

 // Register listener for Debezium's 'Notification' events.
 @EventListener
 public void applicationEventListener(PayloadApplicationEvent<Notification> applicationEvent) {
  System.out.println("APPLICATION EVENT: " + applicationEvent.getPayload());
 }

 // Send Incremental Snapshot as Spring Application Event
 public void scheduleFixedDelayTask() {
  SignalRecord signal = new SignalRecord("'ad-hoc-666", "execute-snapshot",
    "{\"data-collections\": [\"testDB.dbo.orders\", \"testDB.dbo.customers\", \"testDB.dbo.products\"],\"type\":\"incremental\"}",
    null);
  this.publisher.publishEvent(signal);
 }
 // ......
}
```

For a complete example check the [CdcDemoApplication.java](https://github.com/tzolov/spring-debezium-demos/blob/main/src/main/java/com/example/sidebeziumdemo/CdcDemoApplication.java) sample.

### References

- [(blog) Debezium signaling and notifications](https://debezium.io/blog/2023/06/27/Debezium-signaling-and-notifications/)
  > Even when using the Kafka signal approach, the incremental snapshot feature still requires the presence and use of the signaling table.
- [Sending signals to a Debezium connector](https://debezium.io/documentation/reference/configuration/signalling.html)
- [Receiving Debezium notifications](https://debezium.io/documentation/reference/configuration/notification.html)

