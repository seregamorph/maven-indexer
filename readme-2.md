Build
```shell script
mvn clean package -DskipTests -Drat.skip=true -Denforcer.skip=true -Dcheckstyle.skip=true

java -jar indexer-examples/indexer-examples-basic/target/indexer-examples-basic-6.1.0-SNAPSHOT.jar -e
```
