package org.hobbit.benchmark.faceted_browsing.main;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

import org.hobbit.benchmark.faceted_browsing.config.ConfigsFacetedBrowsingBenchmark.BenchmarkLauncher;
import org.hobbit.benchmark.faceted_browsing.config.ConfigsFacetedBrowsingBenchmark.ConfigCommandChannel;
import org.hobbit.benchmark.faceted_browsing.config.ConfigsFacetedBrowsingBenchmark.ConfigDockerServiceFactory;
import org.hobbit.benchmark.faceted_browsing.config.ConfigsFacetedBrowsingBenchmark.ConfigDockerServiceManagerClient;
import org.hobbit.benchmark.faceted_browsing.config.ConfigsFacetedBrowsingBenchmark.ConfigDockerServiceManagerServer;
import org.hobbit.benchmark.faceted_browsing.config.ConfigsFacetedBrowsingBenchmark.ConfigRabbitMqConnection;
import org.hobbit.core.config.ConfigGson;
import org.hobbit.core.config.ConfigRabbitMqConnectionFactory;
import org.hobbit.qpid.v7.config.ConfigQpidBroker;
import org.junit.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public class TestBenchmark {

	@Test
	public void testBenchmark() throws MalformedURLException, IOException {

		// In the local environment:
		// Set up a URL stream handler that maps docker names to localhost
        final String prefix = "docker+";
		URL.setURLStreamHandlerFactory(protocol -> protocol.startsWith(prefix) ? new URLStreamHandler() {
		    protected URLConnection openConnection(URL url) throws IOException {
		        URI uri;
				try {
					uri = url.toURI();
				} catch (URISyntaxException e) {
					throw new RuntimeException(e);
				}
		    	

		        String scheme = uri.getScheme();
		        String host = uri.getHost();

	        	scheme = scheme.substring(prefix.length());
	        	host = "localhost";

		        URL rewrite;
				try {
					rewrite = new URI(scheme, uri.getUserInfo(), host, uri.getPort(),
					           uri.getPath(), uri.getQuery(), uri.getFragment()).toURL();
				} catch (URISyntaxException e) {
					throw new RuntimeException(e);
				}
		        
		        
		    	return rewrite.openConnection();
		    }
		} : null);
		
		
		//System.out.println(CharStreams.toString(new InputStreamReader(new URL("docker+http://foobar:8892/sparql").openStream(), StandardCharsets.UTF_8)));		
		//System.exit(0);
		
		SpringApplicationBuilder builder = new SpringApplicationBuilder()
			// Add the amqp broker
			.sources(ConfigQpidBroker.class)
			// Register the docker service manager server component; for this purpose:
			// (1) Register any pseudo docker images - i.e. launchers of local components
			// (2) Configure a docker service factory - which creates service instances that can be launched
			// (3) configure the docker service manager server component which listens on the amqp infrastructure
			.child(ConfigGson.class, ConfigRabbitMqConnectionFactory.class, ConfigRabbitMqConnection.class, ConfigCommandChannel.class, ConfigDockerServiceFactory.class, ConfigDockerServiceManagerServer.class)
			.sibling(ConfigGson.class, ConfigRabbitMqConnectionFactory.class, ConfigRabbitMqConnection.class, ConfigCommandChannel.class, ConfigDockerServiceManagerClient.class, BenchmarkLauncher.class);
			;

		try(ConfigurableApplicationContext ctx = builder.run()) {}

		
//		.child(ConfigRabbitMqConnectionFactory.class)
//		// Connect the docker service factory to the amqp infrastructure 
//		.child(ConfigDockerServiceFactoryHobbitFacetedBenchmarkLocal.class, ConfigDockerServiceManagerServiceComponent.class) // Connect the local docker service factory to the rabbit mq channels
//		// Add the benchmark component
//		.sibling(ConfigBenchmarkControllerChannels.class, ConfigDockerServiceManagerClientComponent.class, ConfigHobbitFacetedBenchmarkController.class);

	}
}
