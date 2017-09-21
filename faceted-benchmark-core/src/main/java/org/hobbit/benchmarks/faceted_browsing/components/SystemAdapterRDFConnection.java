package org.hobbit.benchmarks.faceted_browsing.components;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Resource;

import org.aksw.jena_sparql_api.stmt.SparqlStmt;
import org.aksw.jena_sparql_api.stmt.SparqlStmtParserImpl;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.query.Syntax;
import org.apache.jena.rdfconnection.RDFConnection;
import org.hobbit.core.Commands;
import org.hobbit.transfer.InputStreamManagerImpl;
import org.hobbit.transfer.Publisher;
import org.hobbit.transfer.StreamManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileCopyUtils;

import com.google.common.base.Stopwatch;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.ServiceManager;

/**
 * TODO Rename to something like TaskExecutorSparql
 *
 * SPARQL based SystemAdapter implementation for Jena's RDFConnection capable systems
 *
 * @author raven Sep 19, 2017
 *
 */
public class SystemAdapterRDFConnection
    extends ComponentBase
{
    private static final Logger logger = LoggerFactory.getLogger(SystemAdapterRDFConnection.class);

    @Resource(name="systemUnderTestRdfConnectionSupplier")
    protected Supplier<RDFConnection> rdfConnectionSupplier;

    @Resource(name="dg2saPub")
    protected Publisher<ByteBuffer> fromDataGenerator;

    @Resource(name="tg2saPub")
    protected Publisher<ByteBuffer> fromTaskGenerator;

    @Resource(name="sa2es")
    protected WritableByteChannel sa2es;

    @Resource(name="commandChannel")
    protected WritableByteChannel commandChannel;


    protected StreamManager streamManager;

    protected ServiceManager serviceManager;

    protected RDFConnection rdfConnection;

//    protected Service systemUnderTestService;

    @Override
    public void init() throws Exception {

        streamManager = new InputStreamManagerImpl(commandChannel);
        // The system adapter will send a ready signal, hence register on it on the command queue before starting the service
        // NOTE A completable future will resolve only once; Java 9 flows would allow multiple resolution (reactive streams)
//        systemUnderTestReadyFuture = PublisherUtils.awaitMessage(commandPublisher,
//                firstByteEquals(Commands.SYSTEM_READY_SIGNAL));

        //systemUnderTestService = systemUnderTestServiceFactory.get();
        //streamManager = new InputStreamManagerImpl(c);
        fromDataGenerator.subscribe(streamManager::handleIncomingData);

        streamManager.subscribe(inputStream -> {
            logger.info("Bulk load data received");

            try(InputStream in = inputStream) {
                // Write incoming data to a file
                File file = File.createTempFile("hobbit-system-adapter-data-to-load", ".nt");
                FileCopyUtils.copy(in, new FileOutputStream(file));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        rdfConnection = rdfConnectionSupplier.get();

        serviceManager = new ServiceManager(Arrays.asList(
//                systemUnderTestService
        ));

        ServiceManagerUtils.startAsyncAndAwaitHealthyAndStopOnFailure(
                serviceManager,
                60, TimeUnit.SECONDS,
                60, TimeUnit.SECONDS);


        fromDataGenerator.subscribe(byteBuffer -> {
            // non-stream messages from the data generator are ignored
            // System.out.println("Got a message form the data generator");
        });

        fromTaskGenerator.subscribe(byteBuffer -> {
            String sparqlStmtStr = new String(byteBuffer.array(), StandardCharsets.UTF_8);

            Function<String, SparqlStmt> parser = SparqlStmtParserImpl.create(Syntax.syntaxSPARQL_11, true);

            SparqlStmt stmt = parser.apply(sparqlStmtStr);

            Stopwatch stopwatch = Stopwatch.createStarted();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if(stmt.isQuery()) {
                try(QueryExecution qe = rdfConnection.query(stmt.getOriginalString())) {
                    ResultSet rs = qe.execSelect();
                    ResultSetFormatter.outputAsJSON(out, rs);
                } catch(Exception e) {
                    logger.warn("Sparql select query failed", e);

                    try {
                        out.write("{\"head\":{\"vars\":[\"xxx\"]},\"results\":{\"bindings\":[{\"xxx\":{\"type\":\"literal\",\"value\":\"XXX\"}}]}}".getBytes(StandardCharsets.UTF_8));
                    } catch (IOException f) {}
                }
            } else if(stmt.isUpdateRequest()) {
                try {
                    rdfConnection.update(stmt.getOriginalString());
                } catch(Exception e) {
                    try {
                        out.write("{\"head\":{\"vars\":[\"xxx\"]},\"results\":{\"bindings\":[{\"xxx\":{\"type\":\"literal\",\"value\":\"XXX\"}}]}}".getBytes(StandardCharsets.UTF_8));
                    } catch (IOException f) {}
                    logger.warn("Sparql update query failed", e);
                }
            }

            long elapsed = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);

            //sendResultToEvalStorage(taskId, outputStream.toByteArray());

            System.out.println("Got a message form the task generator");
        });

        commandChannel.write(ByteBuffer.wrap(new byte[]{Commands.SYSTEM_READY_SIGNAL}));
    }

    @Override
    public void close() throws IOException {
        streamManager.close();
        ServiceManagerUtils.stopAsyncAndWaitStopped(serviceManager, 60, TimeUnit.SECONDS);
    }

}