```
cd src/main/resources/docker/postgres-eos
```

```
docker build -t postgres-eos:last .
```

```
docker run -it --rm --name postgres-eos -p 5432:5432 -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres postgres-eos:last

docker exec -it postgres-eos /bin/sh

docker exec -it postgres-eos /usr/bin/psql -U postgres -h localhost -p 5432
```

```
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND datname = 'postgres' AND query like 'START_REPLICATION SLOT %';
```
