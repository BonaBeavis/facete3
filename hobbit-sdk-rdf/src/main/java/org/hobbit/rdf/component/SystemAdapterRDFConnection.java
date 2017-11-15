package org.hobbit.rdf.component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
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
import org.apache.jena.sparql.resultset.ResultSetMem;
import org.apache.jena.vocabulary.RDFS;
import org.hobbit.core.Commands;
import org.hobbit.core.component.ComponentBase;
import org.hobbit.core.service.api.RunnableServiceCapable;
import org.hobbit.core.utils.ByteChannelUtils;
import org.hobbit.core.utils.PublisherUtils;
import org.hobbit.core.utils.ServiceManagerUtils;
import org.hobbit.transfer.InputStreamManagerImpl;
import org.hobbit.transfer.StreamManager;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.FileCopyUtils;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ServiceManager;
import com.google.gson.Gson;

import io.reactivex.Flowable;

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
    implements RunnableServiceCapable
{
    private static final Logger logger = LoggerFactory.getLogger(SystemAdapterRDFConnection.class);

    @Autowired
    protected Gson gson;
        
    @Resource(name="systemUnderTestRdfConnectionSupplier")
    protected Supplier<RDFConnection> rdfConnectionSupplier;

    @Resource(name="dg2saPub")
    protected Flowable<ByteBuffer> fromDataGenerator;

    @Resource(name="tg2saPub")
    protected Flowable<ByteBuffer> fromTaskGenerator;

    @Resource(name="sa2es")
    protected Subscriber<ByteBuffer> sa2es;

    @Resource(name="commandChannel")
    protected Subscriber<ByteBuffer> commandChannel;


    protected StreamManager streamManager;

    protected ServiceManager serviceManager;

    protected RDFConnection rdfConnection;

//    protected Service systemUnderTestService;

    protected CompletableFuture<?> taskGenerationFinishedFuture;
    
    @Override
    public void startUp() throws Exception {

        taskGenerationFinishedFuture = PublisherUtils.triggerOnMessage(commandPublisher,
                ByteChannelUtils.firstByteEquals(Commands.TASK_GENERATION_FINISHED));

        
        streamManager = new InputStreamManagerImpl(commandChannel::onNext);
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
                
                
                // Load data
                String graphName = "http://www.virtuoso-graph.com";
                logger.debug("Clearing and loading graph: " + graphName);
                rdfConnection.delete(graphName);
                rdfConnection.load(graphName, file.getAbsolutePath());
                file.delete();
                logger.info("Data loading complete");
                
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
            logger.debug("SystemAdapter received a message form the TaskGenerator");

            //rdfConnection = //RDFConnectionFactory.connect(DatasetFactory.create());

            String jsonStr = new String(byteBuffer.array(), StandardCharsets.UTF_8);
            org.apache.jena.rdf.model.Resource r = FacetedBrowsingEncoders.jsonToResource(jsonStr, gson);

            
            String taskIdStr = r.getURI();
            String sparqlStmtStr = r.getProperty(RDFS.label).getString();

            //logger.debug("R"
            //RDFDataMgr.write(System.out, r.getModel(), RDFFormat.TURTLE_PRETTY);
            logger.debug("TaskId - Sparql stmt: " + taskIdStr + " - " + sparqlStmtStr);

            Function<String, SparqlStmt> parser = SparqlStmtParserImpl.create(Syntax.syntaxSPARQL_11, true);

            SparqlStmt stmt = parser.apply(sparqlStmtStr);

            Stopwatch stopwatch = Stopwatch.createStarted();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if(stmt.isQuery()) {
                try(QueryExecution qe = rdfConnection.query(stmt.getOriginalString())) {
                    ResultSet rs = qe.execSelect();
                    ResultSetMem rsMem = new ResultSetMem(rs);
                    int numRows = ResultSetFormatter.consume(rsMem);
                    rsMem.rewind();
                    logger.debug("Number of result set rows for task " + taskIdStr + ": " + numRows + " query: " + stmt.getOriginalString());

                    ResultSetFormatter.outputAsJSON(out, rsMem);
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

            //String taskIdStr = "task-id-foobar";
            byte[] data = out.toByteArray();


            byte[] taskIdBytes = taskIdStr.getBytes(StandardCharsets.UTF_8);
            // + 4 for taskIdBytes.length
            // + 4 for data.length
            int capacity = 4 + 4 + taskIdBytes.length + data.length;
            ByteBuffer buffer = ByteBuffer.allocate(capacity);
            buffer.putInt(taskIdBytes.length);
            buffer.put(taskIdBytes);
            buffer.putInt(data.length);
            buffer.put(data);

//            byte[] taskIdBytes = taskIdStr.getBytes(StandardCharsets.UTF_8);
//            int capacity = 8 + taskIdBytes.length + data.length;
//            ByteBuffer buffer = ByteBuffer.allocate(capacity);
//            buffer.putInt(taskIdBytes.length);
//            buffer.put(taskIdBytes);
//            buffer.putInt(data.length);
//            buffer.put(data);

            buffer.rewind();

//            try {
                logger.debug("Forwarding task result to evaluation storage");
                sa2es.onNext(buffer);
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
        });

        commandChannel.onNext(ByteBuffer.wrap(new byte[]{Commands.SYSTEM_READY_SIGNAL}));
    }

    @Override
    public void shutDown() throws IOException {
        streamManager.close();
        ServiceManagerUtils.stopAsyncAndWaitStopped(serviceManager, 60, TimeUnit.SECONDS);
    }

    @Override
    public void run() throws Exception {
        logger.debug("Waiting for task generation to finish");
        taskGenerationFinishedFuture.get(10, TimeUnit.MINUTES);
//        taskGenerationFinishedFuture.get(60, TimeUnit.SECONDS);

        logger.debug("Task generation finished");
    }

}