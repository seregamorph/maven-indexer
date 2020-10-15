```shell script
docker-compose pull
docker-compose up -d
# docker-compose restart
docker logs -f indexer-mysql
```

```shell script
mysql -u indexer -pindexer -h 127.0.0.1 -P 3306 -D indexer
```
