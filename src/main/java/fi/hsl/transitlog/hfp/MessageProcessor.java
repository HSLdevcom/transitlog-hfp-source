package fi.hsl.transitlog.hfp;

import com.typesafe.config.Config;
import fi.hsl.common.pulsar.IMessageHandler;

import org.apache.pulsar.client.api.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MessageProcessor implements IMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    final ArrayList<HfpData> queue;
    final int QUEUE_MAX_SIZE = 100000;
    final MessageParser parser = MessageParser.newInstance();
    final QueueWriter writer;

    ScheduledExecutorService scheduler;

    private MessageProcessor(QueueWriter writer) {
        queue = new ArrayList<>(QUEUE_MAX_SIZE);
        this.writer = writer;
    }

    public static MessageProcessor newInstance(Config config, QueueWriter writer) throws Exception {
        final long intervalInMs = config.getDuration("application.dumpInterval", TimeUnit.MILLISECONDS);

        MessageProcessor processor = new MessageProcessor(writer);
        log.info("Let's start the dump-executor");
        processor.startDumpExecutor(intervalInMs);
        return processor;
    }

    void startDumpExecutor(long intervalInMs) {
        log.info("Dump interval {} seconds", intervalInMs);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        log.info("Starting result-scheduler");

        scheduler.scheduleAtFixedRate(() -> {
            try {
                dump();
            }
            catch (Exception e) {
                log.error("Failed to check results, closing application", e);
                close(true);
            }
        }, intervalInMs, intervalInMs, TimeUnit.MILLISECONDS);
    }

    private void dump() throws Exception {
        log.debug("Saving results");
        ArrayList<HfpData> copy;
        synchronized (queue) {
            copy = new ArrayList<>(queue);
            queue.clear();
        }

        if (copy.isEmpty()) {
            log.info("Queue empty, no messages to write to database");
        }
        else {
            log.info("Writing {} messages to database", copy.size());
            writer.write(copy);
        }
    }

    @Override
    public void handleMessage(Message message) throws Exception {

    }

    @Override
    public void handleMessage(String topic, MqttMessage message) throws Exception {
        if (queue.size() > QUEUE_MAX_SIZE) {
            //TODO we should somehow tell MQTT not to ack the message. however that doesn't fix the issue though.
            log.error("Queue full: " + QUEUE_MAX_SIZE);
            return;
        }

        Optional<HfpMetadata> maybeMetadata = MessageParser.safeParseMetadata(topic);
        if (!maybeMetadata.isPresent()) {
            log.warn("Failed to parse hfp metadata from MQTT topic");
        }


        Optional<HfpMessage> maybeHfp = parser.safeParse(message);
        if (!maybeHfp.isPresent()) {
            log.warn("Failed to parse hfp payload from MQTT message");
        }

        if (maybeHfp.isPresent() && maybeMetadata.isPresent()) {
            synchronized (queue) {
                queue.add(new HfpData(maybeMetadata.get(), maybeHfp.get()));
            }
        }
        /*
        if (queue.size() > QUEUE_MAX_SIZE) {
            log.warn("Queue full, removing oldest message");
            queue.removeFirst();
        }*/
    }

    @Override
    public void connectionLost(Throwable cause) {
        try {
            log.info("Mqtt connection lost, saving queue to DB before exit");
            dump();
        }
        catch (Exception e) {
            log.error("Failed to dump queue to DB at connectionLost", e);
        }
        //Let mqtt connection handler clean up itself
        close(false);
    }

    public void close(boolean closeMqtt) {
        log.warn("Closing MessageProcessor resources");
        scheduler.shutdown();
        log.info("Scheduler shutdown finished");
        if (closeMqtt) {
            connector.close();
            log.info("MQTT connection closed");
        }
    }

}
