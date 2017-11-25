package org.hobbit.core.component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import javax.annotation.Resource;
import javax.inject.Inject;

import org.apache.jena.rdf.model.Model;
import org.hobbit.core.Commands;
import org.hobbit.core.components.AbstractEvaluationStorage;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.hobbit.core.service.api.RunnableServiceCapable;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.reactivex.Flowable;

@Component
public class EvaluationModuleComponent
    extends ComponentBase
    implements RunnableServiceCapable
{
    private static final Logger logger = LoggerFactory.getLogger(EvaluationModuleComponent.class);

    @Resource(name="commandSender")
    protected Subscriber<ByteBuffer> commandSender;

    @Resource(name="es2emReceiver")
    protected Flowable<ByteBuffer> fromEvaluationStorage;

    @Resource(name="em2esSender")
    protected Subscriber<ByteBuffer> toEvaluationStorage;
    
    //@Resource(name="evaluationModule")
    @Inject
    protected EvaluationModule evaluationModule;
    
    protected byte requestBody[];

    @Override
    public void startUp() throws Exception {
    	super.startUp();
        //collectResponses();


        // TODO Not sure if this properly emulates the protocol to the evaluation storage

        requestBody = new byte[] { AbstractEvaluationStorage.NEW_ITERATOR_ID };


        //EvaluationModuleFacetedBrowsingBenchmark evaluationCore = new EvaluationModuleFacetedBrowsingBenchmark();
        evaluationModule.init();

        boolean terminationConditionSatisfied[] = {false};
        
        fromEvaluationStorage.subscribe(buffer -> {

        	if(terminationConditionSatisfied[0]) {
        		throw new RuntimeException("Got another message after termination message");
        	}

            logger.debug("Received data to evaluate");

            // if the response is empty
            if (!buffer.hasRemaining()) {
                logger.error("Got a completely empty response from the evaluation storage.");
                return;
            }

            requestBody[0] = buffer.get();

            // if the response is empty
            if (!buffer.hasRemaining()) {
                // This is the 'finish' condition
            	terminationConditionSatisfied[0] = true;
            	
                Model model = evaluationModule.summarizeEvaluation();
                logger.info("The result model has " + model.size() + " triples.");

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                model.write(outputStream, "JSONLD");
                ByteBuffer buf = ByteBuffer.allocate(1 + outputStream.size());
                buf.put(Commands.EVAL_MODULE_FINISHED_SIGNAL);
                buf.put(outputStream.toByteArray());
                try {
                    commandSender.onNext(buf);
                } catch(Exception e) {
                    throw new RuntimeException(e);
                }


            } else {
                // If we did not encounter the end condition, request more data
                byte[] data = RabbitMQUtils.readByteArray(buffer);
                long taskSentTimestamp = data.length > 0 ? RabbitMQUtils.readLong(data) : 0;
                byte[] expectedData = RabbitMQUtils.readByteArray(buffer);

                data = RabbitMQUtils.readByteArray(buffer);
                long responseReceivedTimestamp = data.length > 0 ? RabbitMQUtils.readLong(data) : 0;
                byte[] receivedData = RabbitMQUtils.readByteArray(buffer);

                try {
                	evaluationModule.evaluateResponse(expectedData, receivedData, taskSentTimestamp, responseReceivedTimestamp);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                
//                try {
                    toEvaluationStorage.onNext(ByteBuffer.wrap(requestBody));
//                } catch (IOException e1) {
//                    throw new RuntimeException(e1);
//                }                
            }

        });

        commandSender.onNext(ByteBuffer.wrap(new byte[]{Commands.EVAL_MODULE_READY_SIGNAL}));
        
        run();
    }


    @Override
    public void run() throws Exception {
//        try {
            toEvaluationStorage.onNext(ByteBuffer.wrap(requestBody));
//        } catch (IOException e1) {
//            throw new RuntimeException(e1);
//        }
        
        logger.debug("Running evaluation module");
    }

    @Override
    public void shutDown() throws Exception {
    	super.shutDown();
        // TODO Auto-generated method stub

    }

}
