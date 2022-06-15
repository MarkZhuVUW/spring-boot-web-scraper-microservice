package net.markz.webscraper.api.consumers;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import net.markz.webscraper.api.services.DistributedLockService;
import net.markz.webscraper.api.sqs.Message;
import org.slf4j.Logger;

import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;

/**
 * Processor for all lambda events.
 */
@AllArgsConstructor
public abstract class AbstractEventProcessor<T> {
    private final ExecutorService executorService;
    private final AbstractEventErrorHandler errorHandler;
    private final String sqsQueueUrl;
    private final AmazonSQS amazonSQS;
    private final DistributedLockService distributedLockService;

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
    }

    @SneakyThrows
    public void processEvent(final SQSEvent sqsEvent) {
        final var futures = sqsEvent.getRecords()
                .stream()
                .map(msg -> executorService.submit(
                        () -> processMessage(msg)
                ))
                .toList();

        for (var future : futures) {
            future.get();
        }

    }

    private void processMessage(final SQSEvent.SQSMessage message) {
        getLogger().info("Parsing message body={}", message);
        final var parsedMessage = parseMessage(message);
        getLogger().info("Parsed message={}", parsedMessage);

//        final var lockValue = distributedLockService.tryLock(getLockKey(parsedMessage.getData()), getLockTimeout());

        try {


            if(shouldIgnoreMessage(parsedMessage)) {
                getLogger().info("Message received and ignored. Stop processing message body={}", message.getBody());
            }
            processMessage(parsedMessage);
            acknowledge(message);
        } catch(Exception e) {
            getLogger().error("Failed processing message={}. Exception thrown: {}", message, e);
            errorHandler.replayMessage(message);
        } finally {
//            distributedLockService.release(getLockKey(parsedMessage.getData()), lockValue);
        }

    }


    // Subclasses define how the message is processed and parsed.
    public abstract void processMessage(final Message<T> message);

    public abstract Message<T> parseMessage(final SQSEvent.SQSMessage message);

    /**
     * Acknowledge the sqs message, delete it in the queue.
     * @param message
     */
    private void acknowledge(SQSEvent.SQSMessage message) {
        final var req = new DeleteMessageRequest(this.sqsQueueUrl, message.getReceiptHandle());
        this.amazonSQS.deleteMessage(req);
        this.getLogger().info("Message: {} deleted from queue", message);
    }

    public abstract Logger getLogger();

    public abstract boolean shouldIgnoreMessage(final Message<T> message);

    /**
     * Timeout to release the lock.
     * @return
     */
    public abstract long getLockTimeout();

    /**
     * Lock key.
     * @return
     */
    public abstract String getLockKey(T obj);
}
