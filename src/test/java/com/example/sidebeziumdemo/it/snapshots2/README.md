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

The SNAPSHOT1 and SNAPSHOT2 integration test leverage the `source` channel and use JdbcTemplate to write signals into the signal table.

# Signaling and Notifications

- [(blog) Debezium signaling and notifications](https://debezium.io/blog/2023/06/27/Debezium-signaling-and-notifications/)
  > Even when using the Kafka signal approach, the incremental snapshot feature still requires the presence and use of the signaling table.

- [Sending signals to a Debezium connector](https://debezium.io/documentation/reference/configuration/signalling.html)
- [Receiving Debezium notifications](https://debezium.io/documentation/reference/configuration/notification.html)

### References:
- [Debezium Incremental Snapshots](https://debezium.io/documentation/reference/configuration/signalling.html#debezium-signaling-incremental-snapshots)
- [(blog) Incremental Snapshots in Debezium](https://debezium.io/blog/2021/10/07/incremental-snapshots/)
- [(paper) DBLog: A Watermark Based Change-Data-Capture Framework](https://arxiv.org/pdf/2010.12597v1.pdf)