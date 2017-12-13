package org.hobbit.benchmark.faceted_browsing.main;


import java.util.Map;

import org.hobbit.benchmark.faceted_browsing.config.ConfigsFacetedBrowsingBenchmark;
import org.hobbit.core.service.docker.DockerService;
import org.hobbit.core.service.docker.DockerServiceFactory;
import org.hobbit.core.service.docker.SpringEnvironmentUtils;
import org.springframework.core.env.StandardEnvironment;

import com.spotify.docker.client.exceptions.DockerCertificateException;


/**
 * Command line runner for launching a (virtual) docker service
 * 
 * @author raven Dec 12, 2017
 *
 */
public class MainDockerServiceLauncher {

	public static void main(String[] args) throws DockerCertificateException {
		
		// TODO Make it possible to provide the config class from which to obtain the docker service factory
		
		if(args.length != 1) {
			throw new RuntimeException("Exactly 1 argument expected which is the name of a (virtual) docker image to launch");
		}
		
		String imageName = args[0];

		
		// Get the registry and launch an image
		DockerServiceFactory<?> dockerServiceFactory = ConfigsFacetedBrowsingBenchmark.ConfigDockerServiceFactory.createDockerServiceFactory();
		
		Map<String, String> env = SpringEnvironmentUtils.toStringMap(new StandardEnvironment());
		DockerService dockerService = dockerServiceFactory.create(imageName, env);
		
		dockerService.startAsync().awaitTerminated();
	}

//	@Inject
//	protected Environment env;
//	
//	public static class Config {
//		ApplicationRunner appRunner(DockerServiceBuilderFactory<?> dockerServiceBuilderFactory) {
//			return args -> {
//				List<String> nonOptionArgs = args.getNonOptionArgs();
//				if(nonOptionArgs.size() != 1) {
//					throw new RuntimeException("Exactly 1 argument expected which is the name of a (virtual) docker image to launch");
//				}
//				
//				String imageName = nonOptionArgs.get(0);
//
//				Map<String, String> localEnv = null;
//				DockerService dockerService = dockerServiceBuilderFactory.get()
//					.setImageName(imageName)
//					.setLocalEnvironment(localEnv)
//					.get();
//				
//				dockerService.startAsync().awaitTerminated();
//			};
//		}
//	}
//	
//	
//	public static void main(String[] args) {
//		// Get the registry and launch an image
//		
//		try(ConfigurableApplicationContext ctx = new SpringApplicationBuilder()
//			.sources(ConfigDockerServiceFactory.class)
//				.child(Config.class)
//				.run()) {			
//		}
//	}
}
