

[Towards Debezium exactly-once delivery](https://debezium.io/blog/2023/06/22/towards-exactly-once-delivery/)

> Exactly-once semantics (EOS) is currently supported only with Kafka Connect in distributed mode.

- One way to mitigate/minimize the EOS with Embedded Debezium setup we can leverage the `OffsetCommitPolicy.always()` policy -  that will commit offsets as frequently as possible.
This may result in reduced performance, but it has the least potential for seeing source records more than once upon restart.

  The `testOffsetCommitPolicyALWAYS` integration test illustrates this approach.

- Another approach is to implement (in the downstream application) a message `de-duplication`.
For this we need an unique transaction ID. Debezium's Long Serial Number (LSN) is ideal for this job. Furthermore the `debezium.properties.transforms.flatten.add.headers=lsn` can be used to assign the `lsn` to the message header.
Note that the LSN are connector specific!
Then we can use Bloom Filters to improve the performance.


The following SQL expression kills the Debezium connection to the database, e.g. by connecting to Postgres database, effectively emulating a connector failure.

```
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND datname = 'postgres' AND query like 'START_REPLICATION SLOT %';
```
