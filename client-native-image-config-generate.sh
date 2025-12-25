mvn clean package
config_output_dir=client/src/main/resources/META-INF/native-image/com.itjiang/client
app_jar=client/target/client-1.0.1-jar-with-dependencies.jar
java -agentlib:native-image-agent=config-output-dir=${config_output_dir} -jar ${app_jar}
